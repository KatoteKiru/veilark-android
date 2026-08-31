package com.example.veilark.profile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Owns versioned, local geo rule-set generations.
 *
 * Connections never depend on the network: [prepare] loads the last verified
 * generation or installs the APK snapshot. GitHub is contacted only by the
 * explicit [refreshFromGitHub] action. A partial or invalid download never
 * replaces the active generation.
 */
object GeoRoutingAssets {
  private const val DIRECTORY = "geo"
  private const val GENERATIONS = "generations"
  private const val MANIFEST = "current.json"
  private const val MANIFEST_VERSION = 1
  private const val GEOIP_ASSET = "rules/geoip-ru.srs"
  private const val GEOSITE_ASSET = "rules/geosite-category-ru.srs"
  private const val GEOIP_FILE = "geoip-ru.srs"
  private const val GEOSITE_FILE = "geosite-category-ru.srs"
  private const val GEOIP_URL =
    "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs"
  private const val GEOSITE_URL =
    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs"
  private const val MAX_RULE_SET_BYTES = 32L * 1024L * 1024L
  private val GENERATION_PATTERN = Regex("[0-9a-f]{32}")
  private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
  private val lock = Any()
  private val updateMutex = Mutex()

  @Volatile
  private var cached: ProfileSelection.GeoRuleSets? = null

  data class UpdateResult(
    val geoIpSha256: String,
    val geoSiteSha256: String,
  )

  fun prepare(context: Context): ProfileSelection.GeoRuleSets = synchronized(lock) {
    cached?.takeIf { it.filesPresent() }?.let { return@synchronized it }
    loadActive(context) ?: installPackaged(context)
  }.also { cached = it }

  suspend fun refreshFromGitHub(
    context: Context,
    validator: (ProfileSelection.GeoRuleSets) -> Unit,
  ): UpdateResult = withContext(Dispatchers.IO) {
    updateMutex.withLock {
      val root = root(context)
      val generations = File(root, GENERATIONS).also {
        check(it.isDirectory || it.mkdirs()) { "Не удалось создать хранилище геоданных" }
      }
      val generation = UUID.randomUUID().toString().replace("-", "")
      val staging = File(generations, ".staging-$generation")
      val completed = File(generations, generation)
      check(staging.mkdir()) { "Не удалось подготовить обновление геоданных" }
      try {
        val geoIp = File(staging, GEOIP_FILE)
        val geoSite = File(staging, GEOSITE_FILE)
        download(GEOIP_URL, geoIp)
        download(GEOSITE_URL, geoSite)
        val result = UpdateResult(sha256(geoIp), sha256(geoSite))
        val candidate = ProfileSelection.GeoRuleSets(geoIp.absolutePath, geoSite.absolutePath)
        validator(candidate)
        synchronized(lock) {
          move(staging, completed)
          publish(root, generation, result)
          cached = ProfileSelection.GeoRuleSets(
            File(completed, GEOIP_FILE).absolutePath,
            File(completed, GEOSITE_FILE).absolutePath,
          )
          pruneGenerations(generations, generation)
        }
        result
      } finally {
        staging.deleteRecursively()
      }
    }
  }

  private fun loadActive(context: Context): ProfileSelection.GeoRuleSets? = runCatching {
    val root = root(context)
    val manifestFile = File(root, MANIFEST)
    check(manifestFile.isFile && manifestFile.length() in 1..4_096)
    val manifest = JSONObject(manifestFile.readText())
    check(manifest.getInt("version") == MANIFEST_VERSION)
    val generation = manifest.getString("generation").also {
      check(it.matches(GENERATION_PATTERN))
    }
    val geoIpSha = manifest.getString("geoip_sha256").also {
      check(it.matches(SHA256_PATTERN))
    }
    val geoSiteSha = manifest.getString("geosite_sha256").also {
      check(it.matches(SHA256_PATTERN))
    }
    val generations = File(root, GENERATIONS).canonicalFile
    val directory = File(generations, generation).canonicalFile
    check(directory.parentFile == generations)
    val geoIp = File(directory, GEOIP_FILE)
    val geoSite = File(directory, GEOSITE_FILE)
    checkRuleSet(geoIp, geoIpSha)
    checkRuleSet(geoSite, geoSiteSha)
    ProfileSelection.GeoRuleSets(geoIp.absolutePath, geoSite.absolutePath)
  }.getOrNull()

  private fun installPackaged(context: Context): ProfileSelection.GeoRuleSets {
    val root = root(context)
    val generations = File(root, GENERATIONS).also {
      check(it.isDirectory || it.mkdirs()) { "Не удалось создать хранилище геоданных" }
    }
    val generation = UUID.randomUUID().toString().replace("-", "")
    val staging = File(generations, ".staging-$generation")
    val completed = File(generations, generation)
    check(staging.mkdir()) { "Не удалось подготовить файлы геомаршрутизации" }
    try {
      val geoIp = copyAsset(context, GEOIP_ASSET, File(staging, GEOIP_FILE))
      val geoSite = copyAsset(context, GEOSITE_ASSET, File(staging, GEOSITE_FILE))
      val result = UpdateResult(sha256(geoIp), sha256(geoSite))
      move(staging, completed)
      publish(root, generation, result)
      pruneGenerations(generations, generation)
      return ProfileSelection.GeoRuleSets(
        File(completed, GEOIP_FILE).absolutePath,
        File(completed, GEOSITE_FILE).absolutePath,
      )
    } finally {
      staging.deleteRecursively()
    }
  }

  private fun root(context: Context): File = File(context.noBackupFilesDir, DIRECTORY).also {
    check(it.isDirectory || it.mkdirs()) { "Не удалось подготовить файлы геомаршрутизации" }
  }

  private fun copyAsset(context: Context, assetName: String, destination: File): File {
    context.assets.open(assetName).use { input ->
      destination.outputStream().buffered().use(input::copyTo)
    }
    checkRuleSet(destination)
    return destination
  }

  private fun download(url: String, destination: File) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host == "raw.githubusercontent.com") {
      "Недопустимый источник геоданных"
    }
    val connection = uri.toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 8_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Accept", "application/octet-stream")
    connection.setRequestProperty("User-Agent", "Veilark-Android")
    try {
      check(connection.responseCode == HttpURLConnection.HTTP_OK) {
        "GitHub не вернул файл геоданных"
      }
      val declared = connection.contentLengthLong
      check(declared == -1L || declared in 1..MAX_RULE_SET_BYTES) {
        "Некорректный размер файла геоданных"
      }
      connection.inputStream.buffered().use { input ->
        destination.outputStream().buffered().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          var total = 0L
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= MAX_RULE_SET_BYTES) { "Файл геоданных слишком большой" }
            output.write(buffer, 0, read)
          }
        }
      }
      checkRuleSet(destination)
    } finally {
      connection.disconnect()
    }
  }

  private fun publish(root: File, generation: String, result: UpdateResult) {
    val temporary = File(root, "$MANIFEST.tmp")
    temporary.writeText(
      JSONObject()
        .put("version", MANIFEST_VERSION)
        .put("generation", generation)
        .put("geoip_sha256", result.geoIpSha256)
        .put("geosite_sha256", result.geoSiteSha256)
        .toString(),
    )
    move(temporary, File(root, MANIFEST), replace = true)
  }

  private fun move(source: File, destination: File, replace: Boolean = false) {
    val options = buildList {
      add(StandardCopyOption.ATOMIC_MOVE)
      if (replace) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()
    runCatching { Files.move(source.toPath(), destination.toPath(), *options) }
      .getOrElse {
        val fallback = if (replace) {
          arrayOf(StandardCopyOption.REPLACE_EXISTING)
        } else {
          emptyArray()
        }
        Files.move(source.toPath(), destination.toPath(), *fallback)
      }
  }

  private fun checkRuleSet(file: File, expectedSha256: String? = null) {
    check(file.isFile && file.length() in 1..MAX_RULE_SET_BYTES) {
      "Повреждён файл геомаршрутизации"
    }
    if (expectedSha256 != null) {
      check(sha256(file) == expectedSha256) { "Хэш геоданных не совпадает" }
    }
  }

  private fun pruneGenerations(directory: File, active: String) {
    directory.listFiles()
      ?.filter { it.isDirectory && it.name.matches(GENERATION_PATTERN) && it.name != active }
      ?.sortedByDescending(File::lastModified)
      ?.drop(1)
      ?.forEach(File::deleteRecursively)
  }

  private fun ProfileSelection.GeoRuleSets.filesPresent(): Boolean =
    File(geoIpRuPath).isFile && File(geoSiteRuPath).isFile

  private fun sha256(file: File): String = file.inputStream().use(::sha256)

  private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      if (read > 0) digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
