package com.example.veilark.engine

import java.io.File

enum class TunnelEngineKind {
  SING_BOX,
  TRUST_TUNNEL,
}

enum class TunnelStatus {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  FAILED,
}

data class EnginePaths(
  val helper: File,
  val singBox: File,
  val trustTunnel: File,
  val geoIpRu: File,
  val geoIpRuJson: File,
  val geoSiteRu: File,
  val geoSiteRuJson: File,
  val runtimeDir: File,
) {
  fun configFile(kind: TunnelEngineKind): File = File(
    runtimeDir,
    if (kind == TunnelEngineKind.SING_BOX) "sing-box.json" else "trusttunnel.toml",
  )
}

/**
 * Small boundary around the platform-specific privileged helper.
 *
 * Keeping the lifecycle code dependent on this interface lets JVM tests exercise
 * cancellation, state recovery and persistence without pretending to create a
 * macOS TUN device.
 */
interface TunnelController {
  fun installed(): Boolean
  fun enginePresent(kind: TunnelEngineKind): Boolean
  fun install(): Result<Unit>
  fun start(kind: TunnelEngineKind, config: String): Result<Unit>
  fun stop(): Result<Unit>
  fun status(): String
  fun lastLog(): String
  fun tunFailed(): Boolean
  fun outboundUnresolved(): Boolean
}

class PrivilegedHelper(private val paths: EnginePaths) : TunnelController {
  override fun installed(): Boolean = installedHelper().isFile &&
    installedSingBox.isFile &&
    installedTrustTunnel.isFile &&
    versionFile.takeIf { it.isFile }?.readText()?.trim() == VERSION

  override fun enginePresent(kind: TunnelEngineKind): Boolean = when (kind) {
    TunnelEngineKind.SING_BOX -> paths.singBox.isFile
    TunnelEngineKind.TRUST_TUNNEL -> paths.trustTunnel.isFile
  }

  override fun install(): Result<Unit> = runCatching {
    listOf(paths.helper, paths.singBox, paths.trustTunnel).forEach { source ->
      require(source.canonicalFile.isFile) { "Не найден компонент: ${source.name}" }
    }
    val script = """
      set -euo pipefail
      install -d -o root -g wheel -m 0755 /Library/PrivilegedHelperTools/VeilarkEngines
      install -d -o root -g wheel -m 0755 ${shellQuote("/Library/Application Support/Veilark/runtime")}
      install -o root -g admin -m 4750 ${shellQuote(paths.helper.canonicalPath)} /Library/PrivilegedHelperTools/veilark-helper
      install -o root -g wheel -m 0755 ${shellQuote(paths.singBox.canonicalPath)} /Library/PrivilegedHelperTools/VeilarkEngines/sing-box
      install -o root -g wheel -m 0755 ${shellQuote(paths.trustTunnel.canonicalPath)} /Library/PrivilegedHelperTools/VeilarkEngines/trusttunnel_client
      printf '%s\n' ${shellQuote(VERSION)} > ${shellQuote("/Library/Application Support/Veilark/helper.version")}
      chown root:wheel ${shellQuote("/Library/Application Support/Veilark/helper.version")}
      chmod 0644 ${shellQuote("/Library/Application Support/Veilark/helper.version")}
    """.trimIndent()
    val process = ProcessBuilder(
      "osascript",
      "-e",
      "do shell script ${osascriptQuote(script)} with administrator privileges",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output.ifBlank { "Не удалось установить VPN helper" } }
  }

  override fun start(kind: TunnelEngineKind, config: String): Result<Unit> = runCatching {
    paths.runtimeDir.mkdirs()
    val configFile = paths.configFile(kind)
    val temp = File(configFile.parentFile, ".${configFile.name}.tmp")
    temp.writeText(config)
    temp.setReadable(false, false)
    temp.setWritable(false, false)
    temp.setExecutable(false, false)
    check(temp.setReadable(true, true) && temp.setWritable(true, true)) {
      "Не удалось защитить VPN-конфигурацию"
    }
    check(temp.renameTo(configFile) || run {
      configFile.writeBytes(temp.readBytes())
      temp.delete()
      configFile.setReadable(false, false)
      configFile.setWritable(false, false)
      configFile.setReadable(true, true) && configFile.setWritable(true, true)
    }) { "Не удалось сохранить VPN-конфигурацию" }
    invoke("start", engineName(kind), configFile.absolutePath)
  }

  override fun stop(): Result<Unit> = runCatching { invoke("stop") }

  override fun status(): String = runCatching { invoke("status") }.getOrDefault("disconnected")

  override fun lastLog(): String = runCatching { engineLog.readText() }.getOrDefault("")

  override fun tunFailed(): Boolean {
    val log = lastLog()
    return TUN_FAILURES.any { it in log }
  }

  override fun outboundUnresolved(): Boolean {
    val log = lastLog()
    return "empty result" in log ||
      Regex("""outbound connection to :\d+""").containsMatchIn(log)
  }

  private fun invoke(vararg args: String): String {
    val helper = if (installedHelper().isFile) installedHelper() else paths.helper
    val process = ProcessBuilder(listOf(helper.absolutePath) + args.toList())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0) { output.ifBlank { "helper failed: ${args.joinToString(" ")}" } }
    return output
  }

  private fun installedHelper(): File = File("/Library/PrivilegedHelperTools/veilark-helper")

  private fun engineName(kind: TunnelEngineKind): String = when (kind) {
    TunnelEngineKind.SING_BOX -> "sing-box"
    TunnelEngineKind.TRUST_TUNNEL -> "trusttunnel"
  }

  private fun osascriptQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

  companion object {
    const val VERSION = "6"
    private val versionFile = File("/Library/Application Support/Veilark/helper.version")
    private val engineLog = File("/Library/Application Support/Veilark/runtime/engine.log")
    private val installedSingBox = File("/Library/PrivilegedHelperTools/VeilarkEngines/sing-box")
    private val installedTrustTunnel =
      File("/Library/PrivilegedHelperTools/VeilarkEngines/trusttunnel_client")
    private val TUN_FAILURES = listOf(
      "Failed to create listener",
      "Unable to setup routes",
      "Unable to setup routes for mactun session",
      "Failed to initialize tunnel",
      "make_tun_listener",
      "tunnel routes failed",
      "permission denied",
      "SIOCAIFADDR",
    )
  }
}

object BundledPaths {
  fun resolve(): EnginePaths {
    val resources = System.getProperty("compose.application.resources.dir")
      ?.let(::File)
      ?.takeIf { it.isDirectory }
    val root = resources
      ?: File(System.getProperty("user.dir"), "packaging/common")
    val helperBuilt = File(System.getProperty("user.dir"), "build/helper/veilark-helper")
    return EnginePaths(
      helper = firstExisting(helperBuilt, File(root, "veilark-helper"), File(root, "bin/veilark-helper")),
      singBox = firstExisting(File(root, "sing-box"), File(root, "engines/sing-box")),
      trustTunnel = firstExisting(
        File(root, "trusttunnel_client"),
        File(root, "engines/trusttunnel_client"),
      ),
      geoIpRu = firstExisting(File(root, "geo/geoip-ru.srs")),
      geoIpRuJson = firstExisting(File(root, "geo/geoip-ru.json")),
      geoSiteRu = firstExisting(File(root, "geo/geosite-category-ru.srs")),
      geoSiteRuJson = firstExisting(File(root, "geo/geosite-category-ru.json")),
      runtimeDir = File(System.getProperty("user.home"), "Library/Application Support/Veilark/runtime"),
    ).also { paths ->
      listOf(paths.helper, paths.singBox, paths.trustTunnel).forEach { file ->
        if (file.isFile && !file.canExecute()) file.setExecutable(true)
      }
    }
  }

  private fun firstExisting(vararg files: File): File =
    files.firstOrNull(File::isFile) ?: files.first()
}
