package com.example.veilark.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.veilark.BuildConfig
import com.example.veilark.io.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class AppUpdate(
  val versionCode: Int,
  val versionName: String,
  val apkUrl: String,
  val sha256: String,
  val size: Long,
  val notes: String,
)

data class UpdateDownloadProgress(
  val downloadedBytes: Long,
  val totalBytes: Long,
) {
  val fraction: Float
    get() = if (totalBytes <= 0L) 0f else {
      (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}

object UpdateManager {
  private const val MAX_MANIFEST_SIZE = 128 * 1024
  private const val MAX_APK_SIZE = 180L * 1024L * 1024L
  private const val MAX_RELEASE_NOTES_LENGTH = 500
  suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
    if (!BuildConfig.SELF_UPDATE_ENABLED) return@withContext null
    val json = JSONObject(
      fetch(URL(BuildConfig.OTA_MANIFEST_URL), MAX_MANIFEST_SIZE.toLong()).decodeToString(),
    )
    val releaseNotes = json.optString("notes").trim()
    require(releaseNotes.length <= MAX_RELEASE_NOTES_LENGTH) {
      "Описание обновления слишком большое"
    }
    val update = AppUpdate(
      versionCode = json.getInt("versionCode"),
      versionName = json.getString("versionName"),
      apkUrl = json.getString("apkUrl"),
      sha256 = json.getString("sha256").uppercase(),
      size = json.getLong("size"),
      notes = releaseNotes,
    )
    require(update.versionCode > 0 && update.size in 1..MAX_APK_SIZE) {
      "Некорректный манифест обновления"
    }
    val apkUrl = URL(update.apkUrl)
    val apkPort = if (apkUrl.port == -1) apkUrl.defaultPort else apkUrl.port
    require(
      apkUrl.protocol == "https" &&
        apkUrl.host == BuildConfig.OTA_HOST &&
        apkPort == BuildConfig.OTA_PORT
    ) {
      "Недоверенный адрес обновления"
    }
    require(update.sha256.matches(Regex("[0-9A-F]{64}"))) {
      "Некорректная контрольная сумма"
    }
    val signatureV2 = json.optString("signatureV2")
    if (update.versionCode > BuildConfig.VERSION_CODE) {
      require(signatureV2.isNotBlank()) { "Манифест обновления использует устаревшую подпись" }
      verifySignature(canonicalPayloadV2(update), signatureV2)
    } else if (signatureV2.isNotBlank()) {
      verifySignature(canonicalPayloadV2(update), signatureV2)
    } else {
      verifySignature(canonicalPayload(update).toByteArray(Charsets.UTF_8), json.getString("signature"))
    }
    update.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
  }

  fun shouldCheckAutomatically(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (!BuildConfig.SELF_UPDATE_ENABLED) return false
    val preferences = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
    val checkedVersion = preferences.getInt(LAST_CHECK_VERSION, -1)
    val lastCheck = preferences.getLong(LAST_CHECK_TIME, 0L)
    return checkedVersion != BuildConfig.VERSION_CODE ||
      nowMillis - lastCheck !in 0 until AUTO_CHECK_INTERVAL_MS
  }

  fun markCurrentVersionChecked(context: Context, nowMillis: Long = System.currentTimeMillis()) {
    if (!BuildConfig.SELF_UPDATE_ENABLED) return
    context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
      .edit()
      .putInt(LAST_CHECK_VERSION, BuildConfig.VERSION_CODE)
      .putLong(LAST_CHECK_TIME, nowMillis)
      .apply()
  }

  suspend fun download(
    context: Context,
    update: AppUpdate,
    onProgress: suspend (UpdateDownloadProgress) -> Unit = {},
  ): File =
    withContext(Dispatchers.IO) {
      require(BuildConfig.SELF_UPDATE_ENABLED) { "Самообновление отключено в этой сборке" }
      require(update.size in 1..MAX_APK_SIZE) { "Некорректный размер обновления" }
      val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
      val destination = File(updateDir, "veilark-${update.versionCode}.apk")
      val temporary = File(updateDir, ".veilark-${update.versionCode}.download")
      cleanupUpdateCache(updateDir, setOf(destination, temporary))
      if (destination.length() == update.size) {
        runCatching { verifyApk(context, destination, update) }
          .onSuccess {
            onProgress(UpdateDownloadProgress(update.size, update.size))
            return@withContext destination
          }
      }
      if (temporary.length() > update.size) temporary.delete()
      val remaining = (update.size - temporary.length()).coerceAtLeast(0L)
      val available = StatFs(updateDir.path).availableBytes
      require(available >= remaining + MIN_INSTALL_HEADROOM) {
        "Недостаточно свободного места для обновления"
      }
      try {
        streamApk(URL(update.apkUrl), update, temporary, onProgress)
        verifyApk(context, temporary, update)
        if (destination.exists()) require(destination.delete()) {
          "Не удалось заменить старое обновление"
        }
        require(temporary.renameTo(destination)) { "Не удалось сохранить обновление" }
        destination
      } catch (failure: Throwable) {
        if (temporary.length() > update.size) temporary.delete()
        throw failure
      }
    }

  fun canInstallPackages(context: Context): Boolean =
    BuildConfig.SELF_UPDATE_ENABLED && context.packageManager.canRequestPackageInstalls()

  fun installPermissionIntent(context: Context): Intent =
    Intent(
      Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
      Uri.parse("package:${context.packageName}"),
    )

  fun requestInstall(context: Context, apk: File) {
    require(BuildConfig.SELF_UPDATE_ENABLED) { "Самообновление отключено в этой сборке" }
    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.updates",
      apk,
    )
    context.startActivity(
      Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }

  private fun verifySignature(payload: ByteArray, signatureValue: String) {
    val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
      X509EncodedKeySpec(Base64.getDecoder().decode(BuildConfig.OTA_PUBLIC_KEY)),
    )
    val verifier = Signature.getInstance("Ed25519")
    verifier.initVerify(publicKey)
    verifier.update(payload)
    require(verifier.verify(Base64.getDecoder().decode(signatureValue))) {
      "Подпись манифеста обновления недействительна"
    }
  }

  private suspend fun streamApk(
    url: URL,
    update: AppUpdate,
    destination: File,
    onProgress: suspend (UpdateDownloadProgress) -> Unit,
  ) {
    if (destination.length() == update.size) {
      onProgress(UpdateDownloadProgress(update.size, update.size))
      return
    }
    val requestedOffset = destination.length()
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 15_000
      connection.readTimeout = 120_000
      connection.instanceFollowRedirects = false
      connection.useCaches = false
      connection.setRequestProperty("User-Agent", "Veilark/${BuildConfig.VERSION_NAME}")
      if (requestedOffset > 0L) {
        connection.setRequestProperty("Range", "bytes=$requestedOffset-")
      }
      val responseCode = connection.responseCode
      require(
        responseCode == HttpURLConnection.HTTP_OK ||
          (requestedOffset > 0L && responseCode == HTTP_PARTIAL),
      ) {
        "Сервер обновлений ответил ${connection.responseCode}"
      }
      val append = requestedOffset > 0L && responseCode == HTTP_PARTIAL
      val offset = if (append) requestedOffset else 0L
      if (append) {
        require(contentRangeMatches(connection.getHeaderField("Content-Range"), offset, update.size)) {
          "Сервер обновлений вернул неверный диапазон"
        }
      }
      val expectedResponseSize = update.size - offset
      require(
        connection.contentLengthLong == -1L ||
          connection.contentLengthLong == expectedResponseSize,
      ) {
        "Размер APK на сервере не совпадает с манифестом"
      }

      var copied = offset
      var reportedPercent = -1
      suspend fun reportProgress(force: Boolean = false) {
        val percent = ((copied * 100L) / update.size).toInt().coerceIn(0, 100)
        if (force || percent != reportedPercent) {
          reportedPercent = percent
          onProgress(UpdateDownloadProgress(copied, update.size))
        }
      }
      reportProgress(force = true)
      connection.inputStream.buffered(APK_BUFFER_SIZE).use { input ->
        FileOutputStream(destination, append).buffered(APK_BUFFER_SIZE).use { output ->
          val buffer = ByteArray(APK_BUFFER_SIZE)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= update.size) { "Файл обновления больше заявленного размера" }
            output.write(buffer, 0, count)
            reportProgress()
          }
          output.flush()
        }
      }
      require(copied == update.size) { "Обновление загрузилось не полностью" }
      reportProgress(force = true)
    } finally {
      connection.disconnect()
    }
  }

  private fun verifyApk(context: Context, file: File, update: AppUpdate) {
    require(file.length() == update.size) { "Обновление загрузилось не полностью" }
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).buffered(APK_BUFFER_SIZE).use { input ->
      val buffer = ByteArray(APK_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    val actualSha256 = digest.digest().joinToString("") { "%02X".format(it) }
    if (actualSha256 != update.sha256) {
      file.delete()
      error("Контрольная сумма APK не совпадает")
    }
    verifyArchiveIdentity(context, file, update)
  }

  @Suppress("DEPRECATION")
  private fun verifyArchiveIdentity(context: Context, file: File, update: AppUpdate) {
    val packageManager = context.packageManager
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
      ?: run {
        file.delete()
        error("Загруженный файл не является APK")
      }
    requireArchive(file, archive.packageName == context.packageName) {
      "APK предназначен для другого приложения"
    }
    requireArchive(file, archive.longVersionCode == update.versionCode.toLong()) {
      "Версия APK не совпадает с манифестом"
    }

    val installed = packageManager.getPackageInfo(context.packageName, flags)
    val installedSigning = installed.signingInfo
      ?: run {
        file.delete()
        error("Не удалось проверить подпись установленного приложения")
      }
    val archiveSigning = archive.signingInfo
      ?: run {
        file.delete()
        error("APK не имеет проверяемой подписи")
      }
    val installedCurrent = installedSigning.apkContentsSigners.map(::certificateDigest).toSet()
    val archiveCurrent = archiveSigning.apkContentsSigners.map(::certificateDigest).toSet()
    val archiveHistory = if (archiveSigning.hasMultipleSigners()) {
      archiveCurrent
    } else {
      archiveSigning.signingCertificateHistory.orEmpty().map(::certificateDigest).toSet()
    }
    val signerAccepted = if (installedSigning.hasMultipleSigners()) {
      installedCurrent.isNotEmpty() && archiveCurrent == installedCurrent
    } else {
      installedCurrent.size == 1 && installedCurrent.single() in archiveHistory
    }
    requireArchive(file, signerAccepted) {
      "APK подписан неизвестным ключом"
    }
  }

  private fun certificateDigest(signature: android.content.pm.Signature): String =
    MessageDigest.getInstance("SHA-256")
      .digest(signature.toByteArray())
      .joinToString("") { "%02X".format(it) }

  private inline fun requireArchive(file: File, condition: Boolean, lazyMessage: () -> String) {
    if (!condition) {
      file.delete()
      throw IllegalArgumentException(lazyMessage())
    }
  }

  internal fun contentRangeMatches(value: String?, offset: Long, total: Long): Boolean {
    val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    val declaredTotal = match.groupValues[3].toLongOrNull() ?: return false
    return start == offset && end == total - 1L && declaredTotal == total
  }

  private fun cleanupUpdateCache(directory: File, keep: Set<File>) {
    directory.listFiles()?.forEach { file ->
      if (file !in keep && file.isFile) file.delete()
    }
  }

  fun canonicalPayload(update: AppUpdate): String = buildString {
    appendLine(update.versionCode)
    appendLine(update.versionName)
    appendLine(update.apkUrl)
    appendLine(update.sha256)
    append(update.size)
  }

  internal fun canonicalPayloadV2(update: AppUpdate): ByteArray = buildString {
    listOf(
      "veilark-update-v2",
      update.versionCode.toString(),
      update.versionName,
      update.apkUrl,
      update.sha256,
      update.size.toString(),
      update.notes,
    ).forEach { value ->
      append(value.toByteArray(Charsets.UTF_8).size)
      append(':')
      append(value)
      append('\n')
    }
  }.toByteArray(Charsets.UTF_8)

  private fun fetch(url: URL, maxSize: Long): ByteArray {
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 10_000
      connection.readTimeout = 30_000
      connection.instanceFollowRedirects = false
      connection.setRequestProperty("User-Agent", "Veilark/${BuildConfig.VERSION_NAME}")
      require(connection.responseCode == HttpURLConnection.HTTP_OK) {
        "Сервер обновлений ответил ${connection.responseCode}"
      }
      val declared = connection.contentLengthLong
      require(declared == -1L || declared <= maxSize) { "Файл обновления слишком большой" }
      return connection.inputStream.use { input ->
        val bytes = input.readAtMost(
          (maxSize + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        require(bytes.size.toLong() <= maxSize) { "Файл обновления слишком большой" }
        bytes
      }
    } finally {
      connection.disconnect()
    }
  }

  private const val APK_BUFFER_SIZE = 128 * 1024
  private const val MIN_INSTALL_HEADROOM = 64L * 1024L * 1024L
  private const val HTTP_PARTIAL = 206
  private const val UPDATE_PREFERENCES = "update_meta"
  private const val LAST_CHECK_VERSION = "last_check_version"
  private const val LAST_CHECK_TIME = "last_check_time"
  private const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1_000L
  private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
}
