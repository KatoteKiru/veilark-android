package com.example.veilark.update

import com.example.veilark.io.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class MacUpdate(
  val version: String,
  val build: Int,
  val architecture: String,
  val url: String,
  val sha256: String,
  val size: Long,
  val notes: String,
  val signature: String = "",
)

object MacUpdateClient {
  const val CURRENT_VERSION = UpdateChannel.CURRENT_VERSION
  const val CURRENT_BUILD = UpdateChannel.CURRENT_BUILD

  val configured: Boolean
    get() = UpdateChannel.MANIFEST_URL.isNotBlank() && UpdateChannel.PUBLIC_KEY.isNotBlank()

  val gatekeeperRequired: Boolean
    get() = UpdateChannel.REQUIRE_GATEKEEPER

  suspend fun check(): MacUpdate? = withContext(Dispatchers.IO) {
    check(configured) { "Канал обновлений не настроен для этой сборки" }
    val manifestUrl = requireTrustedUri(configuredManifestUri)
    val payload = fetch(manifestUrl, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
    val update = parseAndVerify(payload, UpdateChannel.PUBLIC_KEY)
    update.takeIf(::isNewer)
  }

  suspend fun download(update: MacUpdate): File = withContext(Dispatchers.IO) {
    val source = requireTrustedUri(update.url)
    val downloads = File(System.getProperty("user.home"), "Library/Caches/Veilark/updates").apply {
      check(isDirectory || mkdirs()) { "Не удалось подготовить каталог обновлений" }
    }
    val target = File(downloads, "Veilark-${update.version}.dmg")
    val temp = File(downloads, ".Veilark-${update.version}-${System.nanoTime()}.part")
    try {
      downloadTo(source, temp, update.size)
      require(temp.length() == update.size) { "Размер загруженного обновления не совпадает с манифестом" }
      require(sha256(temp).equals(update.sha256, ignoreCase = true)) {
        "SHA-256 загруженного обновления не совпадает"
      }
      verifyDmg(temp)
      runCatching {
        Files.move(
          temp.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      }.getOrElse {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      target
    } finally {
      temp.delete()
    }
  }

  fun launchInstaller(update: MacUpdate, file: File) {
    require(file.isFile) { "Файл обновления не найден" }
    check(System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
      "Установка OTA доступна только в macOS-сборке"
    }
    val resources = System.getProperty("compose.application.resources.dir")
      ?.takeIf(String::isNotBlank)
      ?.let(::File)
      ?.canonicalFile
      ?: error("Каталог ресурсов приложения не найден")
    val currentApp = appBundleFromResources(resources)
      ?: error("OTA требует установленное приложение Veilark.app")
    val updater = File(resources, "veilark-updater").canonicalFile
    require(updater.isFile && updater.canExecute()) { "Компонент установки обновлений не найден" }
    val logDir = File(System.getProperty("user.home"), "Library/Logs/Veilark").apply { mkdirs() }
    val launchLog = File(logDir, "updater-launch.log")
    ProcessBuilder(
      updater.absolutePath,
      "--dmg", file.canonicalPath,
      "--sha256", update.sha256,
      "--size", update.size.toString(),
      "--version", update.version,
      "--build", update.build.toString(),
      "--architecture", update.architecture,
      "--url", update.url,
      "--notes", update.notes,
      "--signature", update.signature,
      "--current-app", currentApp.canonicalPath,
      "--pid", ProcessHandle.current().pid().toString(),
      "--relaunch", "true",
    )
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog))
      .start()
  }

  internal fun appBundleFromResources(resources: File): File? {
    var candidate: File? = resources.canonicalFile
    while (candidate != null) {
      if (candidate.extension == "app" && candidate.name == "Veilark.app") return candidate
      candidate = candidate.parentFile
    }
    return null
  }

  internal fun parseAndVerify(raw: String, publicKeyBase64: String): MacUpdate {
    val json = JSONObject(raw)
    require(json.getInt("schemaVersion") == 2) { "Версия OTA-манифеста не поддерживается" }
    require(json.getString("platform") == "macos") { "Обновление предназначено для другой платформы" }
    val signature = json.getString("signature").trim()
    val rawSize = json.opt("size") as? Number
    require(
      rawSize != null && rawSize.toDouble().isFinite() && rawSize.toLong().toDouble() == rawSize.toDouble(),
    ) { "Некорректный размер обновления" }
    val update = MacUpdate(
      version = json.getString("version").trim(),
      build = json.getInt("build"),
      architecture = json.getString("architecture").trim().lowercase(),
      url = json.getString("url").trim(),
      sha256 = json.getString("sha256").trim().lowercase(),
      size = rawSize!!.toLong(),
      notes = json.optString("notes").trim().take(MAX_NOTES),
      signature = signature,
    )
    require(update.version.matches(Regex("""\d+\.\d+\.\d+"""))) { "Некорректная версия обновления" }
    require(update.build > 0) { "Некорректный номер сборки" }
    require(update.sha256.matches(Regex("""[0-9a-f]{64}"""))) { "Некорректный SHA-256 обновления" }
    require(update.size in 1..MAX_DMG_BYTES) { "Некорректный размер обновления" }
    requireTrustedUri(update.url)
    require(update.architecture == "universal" || update.architecture == currentArchitecture()) {
      "Обновление не подходит для архитектуры этого Mac"
    }
    val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
      X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)),
    )
    val verifier = Signature.getInstance("Ed25519")
    verifier.initVerify(publicKey)
    verifier.update(canonicalPayload(update))
    require(verifier.verify(Base64.getDecoder().decode(signature))) {
      "Подпись OTA-манифеста недействительна"
    }
    return update
  }

  internal fun canonicalPayload(update: MacUpdate): ByteArray = listOf(
    "2",
    "macos",
    update.version,
    update.build.toString(),
    update.architecture,
    update.url,
    update.sha256,
    update.size.toString(),
    update.notes,
  ).joinToString("\n") { value ->
    "${value.toByteArray(Charsets.UTF_8).size}:$value"
  }.toByteArray(Charsets.UTF_8)

  /** A signed manifest is advisory; it never installs an update on its own. */
  internal fun isNewer(update: MacUpdate): Boolean =
    isNewerThan(update, CURRENT_VERSION, CURRENT_BUILD)

  internal fun isNewerThan(
    update: MacUpdate,
    currentVersion: String,
    currentBuild: Int,
  ): Boolean =
    update.build > currentBuild && compareVersions(update.version, currentVersion) >= 0

  internal fun compareVersions(left: String, right: String): Int {
    val leftParts = left.split('.').map(String::toInt)
    val rightParts = right.split('.').map(String::toInt)
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
      val comparison = (leftParts.getOrElse(index) { 0 })
        .compareTo(rightParts.getOrElse(index) { 0 })
      if (comparison != 0) return comparison
    }
    return 0
  }

  private fun fetch(uri: URI, limit: Int): ByteArray {
    var current = uri
    repeat(MAX_REDIRECTS + 1) { redirect ->
      val connection = URL(current.toString()).openConnection() as HttpURLConnection
      try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        when (val code = connection.responseCode) {
          200 -> return connection.inputStream.use { stream ->
            stream.readAtMost(limit + 1).also { require(it.size <= limit) { "OTA-манифест слишком большой" } }
          }
          in 300..399 -> {
            require(redirect < MAX_REDIRECTS) { "Слишком много перенаправлений OTA" }
            current = requireTrustedUri(current.resolve(connection.getHeaderField("Location") ?: error("OTA redirect без адреса")))
          }
          else -> error("OTA-сервер ответил HTTP $code")
        }
      } finally {
        connection.disconnect()
      }
    }
    error("Не удалось загрузить OTA-манифест")
  }

  private fun downloadTo(uri: URI, target: File, expectedSize: Long) {
    var current = uri
    repeat(MAX_REDIRECTS + 1) { redirect ->
      val connection = URL(current.toString()).openConnection() as HttpURLConnection
      try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        when (val code = connection.responseCode) {
          200 -> {
            require(connection.contentLengthLong == expectedSize) {
              "Content-Length обновления не совпадает с подписанным размером"
            }
            var total = 0L
            connection.inputStream.use { input ->
              target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                  val read = input.read(buffer)
                  if (read < 0) break
                  total += read
                  require(total <= expectedSize) { "Обновление превышает подписанный размер" }
                  output.write(buffer, 0, read)
                }
              }
            }
            require(total == expectedSize) { "Размер полученного обновления не совпадает с манифестом" }
            return
          }
          in 300..399 -> {
            require(redirect < MAX_REDIRECTS) { "Слишком много перенаправлений OTA" }
            current = requireTrustedUri(current.resolve(connection.getHeaderField("Location") ?: error("OTA redirect без адреса")))
          }
          else -> error("Сервер обновлений ответил HTTP $code")
        }
      } finally {
        connection.disconnect()
      }
    }
    error("Не удалось загрузить обновление")
  }

  private fun verifyDmg(file: File) {
    if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return
    val verify = ProcessBuilder("hdiutil", "verify", file.absolutePath)
      .redirectErrorStream(true)
      .start()
    val verifyOutput = verify.inputStream.bufferedReader().readText()
    check(verify.waitFor() == 0) { verifyOutput.ifBlank { "DMG не прошёл проверку целостности" } }
    if (UpdateChannel.REQUIRE_GATEKEEPER) {
      val gatekeeper = ProcessBuilder(
        "spctl", "--assess", "--verbose=2", "--type", "open",
        "--context", "context:primary-signature", file.absolutePath,
      ).redirectErrorStream(true).start()
      val gatekeeperOutput = gatekeeper.inputStream.bufferedReader().readText()
      check(gatekeeper.waitFor() == 0) {
        gatekeeperOutput.ifBlank { "Обновление не прошло Gatekeeper" }
      }
    }
  }

  private fun requireTrustedUri(value: String): URI = requireTrustedUri(URI(value))

  private fun requireTrustedUri(uri: URI): URI = uri.also {
    require(
      configuredManifestUri.scheme.equals("https", ignoreCase = true) &&
        trustedOtaHost.isNotBlank() &&
        configuredManifestUri.userInfo == null &&
        it.scheme.equals("https", ignoreCase = true) &&
        it.userInfo == null &&
        it.host.equals(trustedOtaHost, ignoreCase = true) &&
        effectivePort(it) == trustedOtaPort,
    ) { "OTA разрешает только настроенный HTTPS host и port" }
  }

  private fun effectivePort(uri: URI): Int = when (uri.port) {
    -1 -> 443
    in 1..65_535 -> uri.port
    else -> error("Некорректный OTA port")
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun currentArchitecture(): String = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "x86_64", "amd64" -> "amd64"
    else -> error("Архитектура Mac не поддерживается")
  }

  private const val MAX_MANIFEST_BYTES = 256 * 1024
  private const val MAX_DMG_BYTES = 750L * 1024 * 1024
  private const val MAX_NOTES = 4_000
  private const val MAX_REDIRECTS = 3

  private val configuredManifestUri = URI(UpdateChannel.MANIFEST_URL)
  private val trustedOtaHost = configuredManifestUri.host?.lowercase().orEmpty()
  private val trustedOtaPort = effectivePort(configuredManifestUri)
}
