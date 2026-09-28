package com.example.veilark.profile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Owns versioned, local geo rule-set generations.
 *
 * Connections never depend on the network: [prepare] loads the last verified
 * generation or installs the APK snapshot. The managed mirror is contacted only by the
 * explicit [refreshFromNetwork] action. Its bounded manifest binds both files to one
 * generation; the upstream rule-set branches are only byte-for-byte fallbacks. A partial,
 * mixed, rolled-back, or invalid download never replaces the active generation.
 */
object GeoRoutingAssets {
  private const val DIRECTORY = "geo"
  private const val GENERATIONS = "generations"
  private const val MANIFEST = "current.json"
  private const val GENERATION_MANIFEST = "manifest.json"
  private const val MANIFEST_VERSION = 1
  private const val GEOIP_ASSET = "rules/geoip-ru.srs"
  private const val GEOSITE_ASSET = "rules/geosite-category-ru.srs"
  private const val GEOIP_FILE = "geoip-ru.srs"
  private const val GEOSITE_FILE = "geosite-category-ru.srs"
  private val REMOTE_MANIFEST_URLS = listOf(
    "https://sub.senyasenyavski.uk/veilark/geo/current/manifest.json",
    "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/manifest.json",
  )
  private val GEOIP_URLS = listOf(
    "https://sub.senyasenyavski.uk/veilark/geo/current/geoip-ru.srs",
    "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/geoip-ru.srs",
    "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs",
  )
  private val GEOSITE_URLS = listOf(
    "https://sub.senyasenyavski.uk/veilark/geo/current/geosite-category-ru.srs",
    "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/geosite-category-ru.srs",
    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs",
  )
  private const val MAX_RULE_SET_BYTES = 32L * 1024L * 1024L
  private const val MAX_MANIFEST_BYTES = 16L * 1024L
  private val GENERATION_PATTERN = Regex("[0-9a-f]{32}")
  private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
  private val lock = Any()
  private val updateMutex = Mutex()

  data class UpdateResult(
    val geoIpSha256: String,
    val geoSiteSha256: String,
  )

  fun prepare(context: Context): ProfileSelection.GeoRuleSets = synchronized(lock) {
    loadActive(context) ?: recoverGeneration(context) ?: installPackaged(context)
  }

  suspend fun refreshFromNetwork(
    context: Context,
    validator: (ProfileSelection.GeoRuleSets) -> Unit,
  ): UpdateResult = withContext(Dispatchers.IO) {
    updateMutex.withLock {
      val root = root(context)
      val active = synchronized(lock) {
        val ruleSets = prepare(context)
        val local = readLocalManifest(File(root, MANIFEST))
        local?.sourceGeneratedAt?.let { timestamp ->
          GeoUpdateManifest(
            timestamp,
            GeoUpdateAsset(local.geoIpSha256, File(ruleSets.geoIpRuPath).length()),
            GeoUpdateAsset(local.geoSiteSha256, File(ruleSets.geoSiteRuPath).length()),
          )
        }
      }
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
        val remote = GeoUpdateCandidateSelector.select(
          REMOTE_MANIFEST_URLS,
          active,
          loadManifest = { url ->
            GeoUpdateManifest.parse(downloadTextOne(url, MAX_MANIFEST_BYTES), MAX_RULE_SET_BYTES)
          },
          validateCandidate = { manifest ->
            download(GEOIP_URLS, geoIp, manifest.geoIp)
            download(GEOSITE_URLS, geoSite, manifest.geoSite)
            validator(ProfileSelection.GeoRuleSets(geoIp.absolutePath, geoSite.absolutePath))
          },
        )
        val result = UpdateResult(sha256(geoIp), sha256(geoSite))
        synchronized(lock) {
          writeManifest(File(staging, GENERATION_MANIFEST), generation, result, remote.generatedAt)
          move(staging, completed)
          publish(root, generation, result, remote.generatedAt)
        }
        result
      } finally {
        staging.deleteRecursively()
      }
    }
  }

  private fun loadActive(context: Context): ProfileSelection.GeoRuleSets? = runCatching {
    val root = root(context)
    val manifest = checkNotNull(readLocalManifest(File(root, MANIFEST)))
    loadGeneration(root, manifest)
  }.getOrNull()

  private fun recoverGeneration(context: Context): ProfileSelection.GeoRuleSets? = runCatching {
    val root = root(context)
    val generations = File(root, GENERATIONS).canonicalFile
    val candidates = generations.listFiles()
      ?.filter { it.isDirectory && it.name.matches(GENERATION_PATTERN) }
      ?.sortedByDescending(File::lastModified)
      .orEmpty()
    candidates.firstNotNullOfOrNull { directory ->
      runCatching {
        val manifest = checkNotNull(readLocalManifest(File(directory, GENERATION_MANIFEST)))
        check(manifest.generation == directory.name)
        val ruleSets = loadGeneration(root, manifest)
        publish(
          root,
          manifest.generation,
          UpdateResult(manifest.geoIpSha256, manifest.geoSiteSha256),
          manifest.sourceGeneratedAt,
        )
        ruleSets
      }.getOrNull()
    }
  }.getOrNull()

  private fun loadGeneration(root: File, manifest: LocalManifest): ProfileSelection.GeoRuleSets {
    val generations = File(root, GENERATIONS).canonicalFile
    val directory = File(generations, manifest.generation).canonicalFile
    check(directory.parentFile == generations)
    val geoIp = File(directory, GEOIP_FILE)
    val geoSite = File(directory, GEOSITE_FILE)
    checkRuleSet(geoIp, manifest.geoIpSha256)
    checkRuleSet(geoSite, manifest.geoSiteSha256)
    return ProfileSelection.GeoRuleSets(geoIp.absolutePath, geoSite.absolutePath)
  }

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
      writeManifest(File(staging, GENERATION_MANIFEST), generation, result, null)
      move(staging, completed)
      publish(root, generation, result, null)
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

  private fun download(urls: List<String>, destination: File, expected: GeoUpdateAsset) {
    require(urls.isNotEmpty())
    val succeeded = urls.any { url ->
      destination.delete()
      runCatching {
        downloadOne(url, destination)
        check(destination.length() == expected.size) { "Размер GEO-файла не совпадает с manifest" }
        checkRuleSet(destination, expected.sha256)
      }.isSuccess
    }
    check(succeeded && destination.isFile) { "Не удалось загрузить геоданные" }
  }

  private fun downloadTextOne(url: String, maxBytes: Long): String {
    val uri = GeoUpdateSourcePolicy.requireAllowed(url)
    val connection = uri.toURL().openConnection() as HttpURLConnection
    configure(connection)
    return try {
      check(connection.responseCode == HttpURLConnection.HTTP_OK) {
        "Источник не вернул GEO manifest"
      }
      val declared = connection.contentLengthLong
      check(declared == -1L || declared in 1..maxBytes) {
        "Некорректный размер GEO manifest"
      }
      val output = ByteArrayOutputStream()
      connection.inputStream.buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          total += read
          check(total <= maxBytes) { "GEO manifest слишком большой" }
          output.write(buffer, 0, read)
        }
      }
      check(output.size() > 0) { "Пустой GEO manifest" }
      output.toString(Charsets.UTF_8.name())
    } finally {
      connection.disconnect()
    }
  }

  private fun downloadOne(
    url: String,
    destination: File,
    maxBytes: Long = MAX_RULE_SET_BYTES,
  ) {
    val uri = GeoUpdateSourcePolicy.requireAllowed(url)
    val connection = uri.toURL().openConnection() as HttpURLConnection
    configure(connection)
    try {
      check(connection.responseCode == HttpURLConnection.HTTP_OK) {
        "Источник не вернул файл геоданных"
      }
      val declared = connection.contentLengthLong
      check(declared == -1L || declared in 1..maxBytes) {
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
            check(total <= maxBytes) { "Файл геоданных слишком большой" }
            output.write(buffer, 0, read)
          }
        }
      }
      check(destination.length() in 1..maxBytes) { "Повреждён загруженный файл" }
    } finally {
      connection.disconnect()
    }
  }

  private fun configure(connection: HttpURLConnection) {
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 8_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Accept", "application/octet-stream")
    connection.setRequestProperty("User-Agent", "Veilark-Android")
  }

  private fun publish(
    root: File,
    generation: String,
    result: UpdateResult,
    sourceGeneratedAt: String?,
  ) {
    val temporary = File(root, "$MANIFEST.tmp")
    writeManifest(temporary, generation, result, sourceGeneratedAt)
    move(temporary, File(root, MANIFEST), replace = true)
  }

  private fun writeManifest(
    destination: File,
    generation: String,
    result: UpdateResult,
    sourceGeneratedAt: String?,
  ) {
    val manifest = JSONObject()
      .put("version", MANIFEST_VERSION)
      .put("generation", generation)
      .put("geoip_sha256", result.geoIpSha256)
      .put("geosite_sha256", result.geoSiteSha256)
    if (sourceGeneratedAt != null) manifest.put("source_generated_at", sourceGeneratedAt)
    destination.writeText(manifest.toString())
  }

  private fun readLocalManifest(file: File): LocalManifest? = runCatching {
    check(file.isFile && file.length() in 1..4_096)
    val manifest = JSONObject(file.readText())
    check(manifest.getInt("version") == MANIFEST_VERSION)
    LocalManifest(
      generation = manifest.getString("generation").also { check(it.matches(GENERATION_PATTERN)) },
      geoIpSha256 = manifest.getString("geoip_sha256").also { check(it.matches(SHA256_PATTERN)) },
      geoSiteSha256 = manifest.getString("geosite_sha256").also { check(it.matches(SHA256_PATTERN)) },
      sourceGeneratedAt = manifest.optString("source_generated_at")
        .takeIf(String::isNotBlank),
    )
  }.getOrNull()

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

  private data class LocalManifest(
    val generation: String,
    val geoIpSha256: String,
    val geoSiteSha256: String,
    val sourceGeneratedAt: String?,
  )
}
