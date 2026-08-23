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
  val runtimeDir: File,
) {
  fun configFile(kind: TunnelEngineKind): File = File(
    runtimeDir,
    if (kind == TunnelEngineKind.SING_BOX) "sing-box.json" else "trusttunnel.toml",
  )
}

class PrivilegedHelper(private val paths: EnginePaths) {
  fun installed(): Boolean = installedHelper().isFile &&
    paths.singBox.isFile &&
    paths.trustTunnel.isFile &&
    versionFile.takeIf { it.isFile }?.readText()?.trim() == VERSION

  fun install(): Result<Unit> = runCatching {
    val script = """
      set -euo pipefail
      mkdir -p /Library/PrivilegedHelperTools/VeilarkEngines
      mkdir -p /Library/Application\ Support/Veilark/runtime
      cp '${paths.helper.absolutePath}' /Library/PrivilegedHelperTools/veilark-helper
      cp '${paths.singBox.absolutePath}' /Library/PrivilegedHelperTools/VeilarkEngines/sing-box
      cp '${paths.trustTunnel.absolutePath}' /Library/PrivilegedHelperTools/VeilarkEngines/trusttunnel_client
      chown root:wheel /Library/PrivilegedHelperTools/veilark-helper
      chown -R root:wheel /Library/PrivilegedHelperTools/VeilarkEngines
      chmod 4755 /Library/PrivilegedHelperTools/veilark-helper
      chmod 755 /Library/PrivilegedHelperTools/VeilarkEngines/sing-box
      chmod 755 /Library/PrivilegedHelperTools/VeilarkEngines/trusttunnel_client
      xattr -c /Library/PrivilegedHelperTools/veilark-helper || true
      xattr -cr /Library/PrivilegedHelperTools/VeilarkEngines || true
      echo ${VERSION} > /Library/Application\ Support/Veilark/helper.version
      chmod 644 /Library/Application\ Support/Veilark/helper.version
    """.trimIndent()
    val process = ProcessBuilder(
      "osascript",
      "-e",
      "do shell script ${osascriptQuote(script)} with administrator privileges",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output.ifBlank { "Не удалось установить VPN helper" } }
  }

  fun start(kind: TunnelEngineKind, config: String): Result<Unit> = runCatching {
    paths.runtimeDir.mkdirs()
    val configFile = paths.configFile(kind)
    configFile.writeText(config)
    invoke("start", engineName(kind), configFile.absolutePath)
  }

  fun stop(): Result<Unit> = runCatching { invoke("stop") }

  fun status(): String = runCatching { invoke("status") }.getOrDefault("disconnected")

  fun lastLog(): String = runCatching { engineLog.readText() }.getOrDefault("")

  fun tunFailed(): Boolean {
    val log = lastLog()
    return TUN_FAILURES.any { it in log }
  }

  fun outboundUnresolved(): Boolean {
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

  companion object {
    const val VERSION = "4"
    private val versionFile = File("/Library/Application Support/Veilark/helper.version")
    private val engineLog = File("/Library/Application Support/Veilark/runtime/engine.log")
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
