package com.example.veilark.profile

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** App-private named scenarios, separate from active routing. Last saved scenario is last in the list. */
class CustomGeoProfileStore(private val filesDir: File) {
  companion object {
    const val MAX_PROFILES = 32
    const val MAX_STORE_BYTES = 1024 * 1024
    private const val SCHEMA = "veilark-custom-geo-store-v1"
  }
  private val file get() = File(filesDir, "custom-geo-profile.json")

  @Synchronized fun load(): CustomGeoProfile? = list().lastOrNull()

  @Synchronized fun list(): List<CustomGeoProfile> {
    if (!file.exists()) return emptyList()
    require(file.length() <= MAX_STORE_BYTES) { "Хранилище GEO-профилей слишком большое" }
    return try {
      val text = CustomGeoProfile.decodeUtf8(file.readBytes())
      // Bound depth before JSONTokener recursion, including a damaged local store.
      CustomGeoProfile.requireBoundedDepth(text)
      val tokener = JSONTokener(text)
      val root = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException()
      require(tokener.nextClean() == '\u0000')
      if (root.opt("schema") == CustomGeoProfile.SCHEMA) {
        // A former single draft is read unchanged; only an atomic successful save migrates it.
        listOf(CustomGeoProfile.parse(text))
      } else {
        require(root.opt("schema") == SCHEMA)
        require(root.keys().asSequence().toSet() == setOf("schema", "profiles"))
        val profiles = root.getJSONArray("profiles")
        require(profiles.length() <= MAX_PROFILES)
        val parsed = (0 until profiles.length()).map { CustomGeoProfile.parse(profiles.getJSONObject(it).toString()) }
        require(parsed.map { it.name }.distinct().size == parsed.size)
        parsed
      }
    } catch (_: Exception) {
      throw IllegalArgumentException("Хранилище GEO-профилей повреждено; сохранение отменено, исходный файл сохранён")
    }
  }

  @Synchronized fun save(profile: CustomGeoProfile) {
    val checked = CustomGeoProfile.create(profile.name, profile.directEntries, profile.vpnEntries)
    val profiles = list().filterNot { it.name == checked.name } + checked
    require(profiles.size <= MAX_PROFILES) { "Можно сохранить не более 32 GEO-профилей" }
    publish(profiles)
  }

  @Synchronized fun remove(name: String) {
    val profiles = list()
    val retained = profiles.filterNot { it.name == name }
    if (retained.size != profiles.size) publish(retained)
  }

  private fun publish(profiles: List<CustomGeoProfile>) {
    val json = JSONObject().put("schema", SCHEMA)
      .put("profiles", JSONArray(profiles.map { JSONObject(it.toJson()) })).toString().toByteArray(Charsets.UTF_8)
    require(json.size <= MAX_STORE_BYTES) { "Хранилище GEO-профилей превышает 1 МБ; прежние профили сохранены" }
    require(filesDir.isDirectory || filesDir.mkdirs()) { "Не удалось создать хранилище GEO-профиля" }
    val temporary = File.createTempFile("custom-geo-", ".tmp", filesDir)
    try {
      FileOutputStream(temporary).use { stream -> stream.write(json); stream.fd.sync() }
      Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: Exception) { throw IllegalArgumentException("Не удалось сохранить GEO-профиль; прежний профиль сохранён")
    } finally { temporary.delete() }
  }
}
