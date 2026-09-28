package com.example.veilark.profile

import com.example.veilark.engine.BundledPaths
import com.example.veilark.protocol.GeoIpRuCatalog
import com.example.veilark.protocol.GeoSiteRuCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GeoRoutingBundle(
  val geoIpSrs: File,
  val geoSiteSrs: File,
  val geoIpJson: File,
  val geoSiteJson: File,
)

interface GeoRoutingStore {
  fun currentOrBundled(): GeoRoutingBundle
  suspend fun refreshFromRemote(): GeoRoutingBundle
}

/** Versioned local GEO cache with a bundled last-known-good fallback. */
class GeoRoutingRepository(
  private val root: File = File(
    System.getProperty("user.home"),
    "Library/Application Support/Veilark/geo",
  ),
  private val connectionFactory: (URI) -> HttpURLConnection = {
    it.toURL().openConnection() as HttpURLConnection
  },
  private val singBoxProvider: () -> File = { BundledPaths.resolve().singBox },
  private val ruleSetDecompiler: ((File, File, File) -> Unit)? = null,
) : GeoRoutingStore {
  private val lock = Any()
  private val updateMutex = Mutex()
  @Volatile private var cached: GeoRoutingBundle? = null

  override fun currentOrBundled(): GeoRoutingBundle = synchronized(lock) {
    cached?.takeIf(::filesPresent)
      ?: (loadActive() ?: bundled().also(::validateBundle)).also { cached = it }
  }

  override suspend fun refreshFromRemote(): GeoRoutingBundle = withContext(Dispatchers.IO) {
    updateMutex.withLock {
      val generations = File(root, GENERATIONS).also {
        check(it.isDirectory || it.mkdirs()) { "Could not create GEO cache" }
      }
      val generation = UUID.randomUUID().toString().replace("-", "")
      val staging = File(generations, ".staging-$generation")
      val completed = File(generations, generation)
      check(staging.mkdir()) { "Could not stage GEO update" }
      try {
        val bundle = GeoRoutingBundle(
          geoIpSrs = File(staging, GEOIP_SRS),
          geoSiteSrs = File(staging, GEOSITE_SRS),
          geoIpJson = File(staging, GEOIP_JSON),
          geoSiteJson = File(staging, GEOSITE_JSON),
        )
        val prepared = prepareRemote(bundle)
        prepared.existing?.let { return@withLock it }
        val hashes = hashes(bundle)
        synchronized(lock) {
          move(staging, completed)
          publish(generation, hashes, prepared.manifest.generatedAt)
          cached = bundleAt(completed)
        }
        val active = bundleAt(completed)
        active
      } finally {
        staging.deleteRecursively()
      }
    }
  }

  private fun loadActive(): GeoRoutingBundle? = runCatching {
    val manifestFile = File(root, MANIFEST)
    check(manifestFile.isFile && manifestFile.length() in 1..8_192)
    val manifest = JSONObject(manifestFile.readText())
    check(manifest.getInt("version") == MANIFEST_VERSION)
    val generation = manifest.getString("generation").also {
      check(it.matches(GENERATION_PATTERN))
    }
    val generations = File(root, GENERATIONS).canonicalFile
    val directory = File(generations, generation).canonicalFile
    check(directory.parentFile == generations)
    val bundle = bundleAt(directory)
    val expected = BundleHashes(
      manifest.getString("geoip_srs_sha256"),
      manifest.getString("geosite_srs_sha256"),
      manifest.getString("geoip_json_sha256"),
      manifest.getString("geosite_json_sha256"),
    )
    expected.values().forEach { check(it.matches(SHA256_PATTERN)) }
    check(hashes(bundle) == expected)
    validateBundle(bundle)
    bundle
  }.getOrNull()

  private fun bundled(): GeoRoutingBundle = BundledPaths.resolve().let { paths ->
    GeoRoutingBundle(paths.geoIpRu, paths.geoSiteRu, paths.geoIpRuJson, paths.geoSiteRuJson)
  }

  private fun bundleAt(directory: File) = GeoRoutingBundle(
    File(directory, GEOIP_SRS),
    File(directory, GEOSITE_SRS),
    File(directory, GEOIP_JSON),
    File(directory, GEOSITE_JSON),
  )

  private fun validateBundle(bundle: GeoRoutingBundle) {
    listOf(bundle.geoIpSrs, bundle.geoSiteSrs).forEach {
      check(it.isFile && it.length() in 1..MAX_SRS_BYTES) { "Invalid GEO rule set" }
    }
    listOf(bundle.geoIpJson, bundle.geoSiteJson).forEach {
      check(it.isFile && it.length() in 1..MAX_JSON_BYTES) { "Invalid GEO catalog" }
    }
    GeoIpRuCatalog.load(bundle.geoIpJson)
    GeoSiteRuCatalog.load(bundle.geoSiteJson)
  }

  private fun filesPresent(bundle: GeoRoutingBundle): Boolean =
    listOf(bundle.geoIpSrs, bundle.geoSiteSrs).all { it.isFile && it.length() in 1..MAX_SRS_BYTES } &&
      listOf(bundle.geoIpJson, bundle.geoSiteJson).all { it.isFile && it.length() in 1..MAX_JSON_BYTES }

  private fun prepareRemote(bundle: GeoRoutingBundle): PreparedRemote {
    val failures = mutableListOf<Throwable>()
    MANIFEST_URLS.forEach { url ->
      runCatching {
        val remote = parseRemoteManifest(readOne(url, MAX_MANIFEST_BYTES))
        val existing = verifyRemoteVersion(remote)
        if (existing != null) return PreparedRemote(remote, existing)
        listOf(bundle.geoIpSrs, bundle.geoSiteSrs, bundle.geoIpJson, bundle.geoSiteJson)
          .forEach(File::delete)
        download(GEOIP_URLS, bundle.geoIpSrs, remote.geoIp)
        download(GEOSITE_URLS, bundle.geoSiteSrs, remote.geoSite)
        val singBox = singBoxProvider()
        decompile(singBox, bundle.geoIpSrs, bundle.geoIpJson)
        decompile(singBox, bundle.geoSiteSrs, bundle.geoSiteJson)
        validateBundle(bundle)
        return PreparedRemote(remote)
      }
        .onFailure(failures::add)
    }
    throw IllegalStateException("GEO update failed for all mirrors", failures.lastOrNull())
  }

  private fun parseRemoteManifest(bytes: ByteArray): RemoteManifest {
    val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
    check(manifest.getInt("schema") == REMOTE_MANIFEST_VERSION) { "Unsupported GEO manifest" }
    val generatedAt = manifest.getString("generatedAt")
    check(generatedAt.matches(GENERATED_AT_PATTERN)) { "Invalid GEO manifest" }
    val assets = manifest.getJSONObject("assets")
    fun asset(name: String): RemoteAsset {
      val value = assets.getJSONObject(name)
      val sha256 = value.getString("sha256").lowercase()
      val size = value.getLong("size")
      check(sha256.matches(SHA256_PATTERN) && size in 1..MAX_SRS_BYTES) {
        "Invalid GEO manifest asset"
      }
      return RemoteAsset(sha256, size)
    }
    return RemoteManifest(generatedAt, asset(GEOIP_SRS), asset(GEOSITE_SRS))
  }

  private fun verifyRemoteVersion(remote: RemoteManifest): GeoRoutingBundle? = synchronized(lock) {
    val local = File(root, MANIFEST)
    if (!local.isFile) return@synchronized null
    val active = loadActive() ?: return@synchronized null
    val manifest = JSONObject(local.readText())
    val previous = manifest.optString("source_generated_at")
    if (previous.isBlank()) return@synchronized null // Earlier clients did not record source version.
    check(previous.matches(GENERATED_AT_PATTERN) && remote.generatedAt >= previous) {
      "GEO mirror is older than the installed generation"
    }
    if (remote.generatedAt == previous) {
      check(remote.geoIp.sha256 == manifest.getString("geoip_srs_sha256") &&
        remote.geoSite.sha256 == manifest.getString("geosite_srs_sha256")) {
        "GEO mirror changed files without a new generation"
      }
      return@synchronized active
    }
    null
  }

  private fun download(urls: List<String>, destination: File, expected: RemoteAsset) {
    require(urls.isNotEmpty())
    val succeeded = urls.any { url ->
      destination.delete()
      runCatching {
        destination.writeBytes(readOne(url, MAX_SRS_BYTES))
        check(destination.length() == expected.size && sha256(destination) == expected.sha256) {
          "GEO checksum mismatch"
        }
      }.isSuccess
    }
    check(succeeded && destination.isFile) { "GEO download failed" }
  }

  private fun readOne(url: String, maximumBytes: Long): ByteArray {
    val uri = URI(url)
    require(uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null)
    require(
      (uri.host == "raw.githubusercontent.com" && uri.port == -1) ||
        (uri.host == "sub.senyasenyavski.uk" && uri.port in setOf(-1, 443)) ||
        (uri.host == "nl2.senyasenyavski.uk" && uri.port == 2096),
    )
    val connection = connectionFactory(uri)
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 8_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Accept", "application/octet-stream")
    connection.setRequestProperty("User-Agent", "Veilark-macOS")
    try {
      check(connection.responseCode == HttpURLConnection.HTTP_OK) { "GEO download failed" }
      val declared = connection.contentLengthLong
      check(declared == -1L || declared in 1..maximumBytes)
      return connection.inputStream.buffered().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          total += read
          check(total <= maximumBytes) { "GEO response is too large" }
          output.write(buffer, 0, read)
        }
        check(total > 0) { "GEO response is empty" }
        output.toByteArray()
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun decompile(executable: File, source: File, destination: File) {
    ruleSetDecompiler?.let {
      it(executable, source, destination)
      return
    }
    check(executable.isFile && executable.canExecute()) { "sing-box is unavailable" }
    val process = ProcessBuilder(
      executable.absolutePath,
      "rule-set",
      "decompile",
      source.absolutePath,
      "--output",
      destination.absolutePath,
    ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    check(process.waitFor(20, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      "GEO validation timed out"
    }
    check(process.exitValue() == 0 && destination.isFile) { "Invalid GEO rule set" }
  }

  private fun publish(generation: String, hashes: BundleHashes, sourceGeneratedAt: String) {
    check(root.isDirectory || root.mkdirs())
    val temporary = File(root, "$MANIFEST.tmp")
    temporary.writeText(
      JSONObject()
        .put("version", MANIFEST_VERSION)
        .put("generation", generation)
        .put("source_generated_at", sourceGeneratedAt)
        .put("geoip_srs_sha256", hashes.geoIpSrs)
        .put("geosite_srs_sha256", hashes.geoSiteSrs)
        .put("geoip_json_sha256", hashes.geoIpJson)
        .put("geosite_json_sha256", hashes.geoSiteJson)
        .toString(),
    )
    move(temporary, File(root, MANIFEST), replace = true)
  }

  private fun hashes(bundle: GeoRoutingBundle) = BundleHashes(
    sha256(bundle.geoIpSrs),
    sha256(bundle.geoSiteSrs),
    sha256(bundle.geoIpJson),
    sha256(bundle.geoSiteJson),
  )

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun move(source: File, destination: File, replace: Boolean = false) {
    val options = buildList {
      add(StandardCopyOption.ATOMIC_MOVE)
      if (replace) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()
    runCatching { Files.move(source.toPath(), destination.toPath(), *options) }
      .getOrElse {
        Files.move(
          source.toPath(),
          destination.toPath(),
          *if (replace) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray(),
        )
      }
  }

  private data class BundleHashes(
    val geoIpSrs: String,
    val geoSiteSrs: String,
    val geoIpJson: String,
    val geoSiteJson: String,
  ) {
    fun values() = listOf(geoIpSrs, geoSiteSrs, geoIpJson, geoSiteJson)
  }

  private data class RemoteAsset(val sha256: String, val size: Long)

  private data class RemoteManifest(
    val generatedAt: String,
    val geoIp: RemoteAsset,
    val geoSite: RemoteAsset,
  )

  private data class PreparedRemote(val manifest: RemoteManifest, val existing: GeoRoutingBundle? = null)

  private companion object {
    const val GENERATIONS = "generations"
    const val MANIFEST = "current.json"
    const val MANIFEST_VERSION = 1
    const val GEOIP_SRS = "geoip-ru.srs"
    const val GEOSITE_SRS = "geosite-category-ru.srs"
    const val GEOIP_JSON = "geoip-ru.json"
    const val GEOSITE_JSON = "geosite-category-ru.json"
    val MANIFEST_URLS = listOf(
      "https://sub.senyasenyavski.uk/veilark/geo/current/manifest.json",
      "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/manifest.json",
    )
    val GEOIP_URLS = listOf(
      "https://sub.senyasenyavski.uk/veilark/geo/current/geoip-ru.srs",
      "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/geoip-ru.srs",
      "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs",
    )
    val GEOSITE_URLS = listOf(
      "https://sub.senyasenyavski.uk/veilark/geo/current/geosite-category-ru.srs",
      "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/geosite-category-ru.srs",
      "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs",
    )
    const val MAX_SRS_BYTES = 32L * 1024L * 1024L
    const val MAX_JSON_BYTES = 64L * 1024L * 1024L
    const val MAX_MANIFEST_BYTES = 16L * 1024L
    const val REMOTE_MANIFEST_VERSION = 1
    val GENERATION_PATTERN = Regex("[0-9a-f]{32}")
    val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    val GENERATED_AT_PATTERN = Regex("\\d{8}T\\d{6}Z")
  }
}
