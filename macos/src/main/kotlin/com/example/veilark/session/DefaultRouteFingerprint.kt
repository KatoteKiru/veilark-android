package com.example.veilark.session

import java.io.BufferedReader
import com.example.veilark.io.BoundedProcess

/** Snapshot of the physical default route used to build the macOS tunnel config. */
data class DefaultRouteFingerprint(
  val interfaceName: String,
  val gateway: String?,
) {
  init {
    require(interfaceName.isNotBlank() && !interfaceName.startsWith("utun"))
  }
}

fun interface DefaultRouteFingerprintProvider {
  fun current(): DefaultRouteFingerprint?
}

/** Small seam: unit tests do not need a macOS route command or a live network. */
object MacDefaultRouteFingerprintProvider : DefaultRouteFingerprintProvider {
  override fun current(): DefaultRouteFingerprint? = currentFromScutil() ?: currentFromRoute()

  /**
   * Dynamic Store keeps the physical primary interface even while a TUN owns
   * the default route. `route get default` alone can otherwise report utun.
   */
  private fun currentFromScutil(): DefaultRouteFingerprint? = runCatching {
    val result = BoundedProcess.run(listOf("scutil"), 3_000,
      "open\nshow State:/Network/Global/IPv4\nquit\n")
    if (result.exitCode == 0) parseScutil(result.text) else null
  }.getOrNull()

  private fun currentFromRoute(): DefaultRouteFingerprint? = runCatching {
    val result = BoundedProcess.run(listOf("route", "-n", "get", "default"), 3_000)
    if (result.exitCode == 0) result.text.reader().buffered().use(::parse) else null
  }.getOrNull()

  internal fun parseScutil(output: String): DefaultRouteFingerprint? {
    val fields = SCUTIL_FIELD.findAll(output).associate { match ->
      match.groupValues[1] to match.groupValues[2]
    }
    return fields["PrimaryInterface"]
      ?.takeIf { it.isNotBlank() && !it.startsWith("utun") }
      ?.let { DefaultRouteFingerprint(it, fields["Router"]) }
  }

  internal fun parse(reader: BufferedReader): DefaultRouteFingerprint? {
    var interfaceName: String? = null
    var gateway: String? = null
    reader.lineSequence().forEach { line ->
      val key = line.substringBefore(':').trim()
      val value = line.substringAfter(':', "").trim()
      when (key) {
        "interface" -> interfaceName = value
        "gateway" -> gateway = value.takeIf(String::isNotBlank)
      }
    }
    return interfaceName
      ?.takeIf { it.isNotBlank() && !it.startsWith("utun") }
      ?.let { DefaultRouteFingerprint(it, gateway) }
  }

  private val SCUTIL_FIELD = Regex("""(?m)^\s*(PrimaryInterface|Router)\s*:\s*(\S+)\s*$""")
}
