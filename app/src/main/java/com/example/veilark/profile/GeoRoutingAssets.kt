package com.example.veilark.profile

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Installs the packaged, offline geo rule sets into a path readable by sing-box. */
object GeoRoutingAssets {
  private const val DIRECTORY = "geo"
  private const val GEOIP_ASSET = "rules/geoip-ru.srs"
  private const val GEOSITE_ASSET = "rules/geosite-category-ru.srs"

  @Volatile
  private var cached: ProfileSelection.GeoRuleSets? = null

  @Synchronized
  fun prepare(context: Context): ProfileSelection.GeoRuleSets {
    cached?.takeIf { it.filesPresent() }?.let { return it }

    val directory = File(context.noBackupFilesDir, DIRECTORY)
    check(directory.isDirectory || directory.mkdirs()) {
      "Не удалось подготовить файлы геомаршрутизации"
    }
    val geoIp = install(context, GEOIP_ASSET, File(directory, "geoip-ru.srs"))
    val geoSite = install(
      context,
      GEOSITE_ASSET,
      File(directory, "geosite-category-ru.srs"),
    )
    return ProfileSelection.GeoRuleSets(
      geoIpRuPath = geoIp.absolutePath,
      geoSiteRuPath = geoSite.absolutePath,
    ).also { cached = it }
  }

  private fun install(context: Context, assetName: String, destination: File): File {
    val packagedDigest = context.assets.open(assetName).use(::sha256)
    if (destination.isFile && destination.inputStream().use(::sha256) == packagedDigest) {
      return destination
    }

    val temporary = File(destination.parentFile, ".${destination.name}.tmp")
    runCatching {
      context.assets.open(assetName).use { input ->
        temporary.outputStream().buffered().use { output -> input.copyTo(output) }
      }
      check(temporary.inputStream().use(::sha256) == packagedDigest) {
        "Повреждён файл геомаршрутизации"
      }
      runCatching {
        Files.move(
          temporary.toPath(),
          destination.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      }.getOrElse {
        Files.move(
          temporary.toPath(),
          destination.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
        )
      }
    }.onFailure {
      temporary.delete()
    }.getOrThrow()
    return destination
  }

  private fun ProfileSelection.GeoRuleSets.filesPresent(): Boolean =
    File(geoIpRuPath).isFile && File(geoSiteRuPath).isFile

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
