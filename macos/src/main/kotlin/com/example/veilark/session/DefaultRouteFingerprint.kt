package com.example.veilark.session

import java.io.BufferedReader

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
    val process = ProcessBuilder("scutil")
      .redirectErrorStream(true)
      .start()
    process.outputStream.bufferedWriter().use { input ->
      input.write("open\nshow State:/Network/Global/IPv4\nquit\n")
    }
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() == 0) parseScutil(output) else null
  }.getOrNull()

  private fun currentFromRoute(): DefaultRouteFingerprint? = runCatching {
    ProcessBuilder("route", "-n", "get", "default")
      .redirectErrorStream(true)
      .start()
      .inputStream
      .bufferedReader()
      .use(::parse)
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
