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

/** Versioned local GEO cache with a bundled last-known-good fallback. */
class GeoRoutingRepository(
  private val root: File = File(
    System.getProperty("user.home"),
    "Library/Application Support/Veilark/geo",
  ),
) {
  private val lock = Any()
  private val updateMutex = Mutex()

  fun currentOrBundled(): GeoRoutingBundle = synchronized(lock) {
    loadActive() ?: bundled().also(::validateBundle)
  }

  suspend fun refreshFromGitHub(): GeoRoutingBundle = withContext(Dispatchers.IO) {
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
        download(GEOIP_URL, bundle.geoIpSrs)
        download(GEOSITE_URL, bundle.geoSiteSrs)
        val singBox = BundledPaths.resolve().singBox
        decompile(singBox, bundle.geoIpSrs, bundle.geoIpJson)
        decompile(singBox, bundle.geoSiteSrs, bundle.geoSiteJson)
        validateBundle(bundle)
        val hashes = hashes(bundle)
        synchronized(lock) {
          move(staging, completed)
          publish(generation, hashes)
          prune(generations, generation)
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

  private fun download(url: String, destination: File) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host == "raw.githubusercontent.com")
    val connection = uri.toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 8_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Accept", "application/octet-stream")
    connection.setRequestProperty("User-Agent", "Veilark-macOS")
    try {
      check(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub download failed" }
      val declared = connection.contentLengthLong
      check(declared == -1L || declared in 1..MAX_SRS_BYTES)
      connection.inputStream.buffered().use { input ->
        destination.outputStream().buffered().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          var total = 0L
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= MAX_SRS_BYTES) { "GEO rule set is too large" }
            output.write(buffer, 0, read)
          }
        }
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun decompile(executable: File, source: File, destination: File) {
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

  private fun publish(generation: String, hashes: BundleHashes) {
    check(root.isDirectory || root.mkdirs())
    val temporary = File(root, "$MANIFEST.tmp")
    temporary.writeText(
      JSONObject()
        .put("version", MANIFEST_VERSION)
        .put("generation", generation)
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

  private fun prune(directory: File, active: String) {
    directory.listFiles()
      ?.filter { it.isDirectory && it.name.matches(GENERATION_PATTERN) && it.name != active }
      ?.sortedByDescending(File::lastModified)
      ?.drop(1)
      ?.forEach(File::deleteRecursively)
  }

  private data class BundleHashes(
    val geoIpSrs: String,
    val geoSiteSrs: String,
    val geoIpJson: String,
    val geoSiteJson: String,
  ) {
    fun values() = listOf(geoIpSrs, geoSiteSrs, geoIpJson, geoSiteJson)
  }

  private companion object {
    const val GENERATIONS = "generations"
    const val MANIFEST = "current.json"
    const val MANIFEST_VERSION = 1
    const val GEOIP_SRS = "geoip-ru.srs"
    const val GEOSITE_SRS = "geosite-category-ru.srs"
    const val GEOIP_JSON = "geoip-ru.json"
    const val GEOSITE_JSON = "geosite-category-ru.json"
    const val GEOIP_URL =
      "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs"
    const val GEOSITE_URL =
      "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs"
    const val MAX_SRS_BYTES = 32L * 1024L * 1024L
    const val MAX_JSON_BYTES = 64L * 1024L * 1024L
    val GENERATION_PATTERN = Regex("[0-9a-f]{32}")
    val SHA256_PATTERN = Regex("[0-9a-f]{64}")
  }
}
