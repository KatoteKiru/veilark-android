package com.example.veilark.profile

import org.json.JSONObject

internal data class GeoUpdateAsset(
  val sha256: String,
  val size: Long,
)

internal data class GeoUpdateManifest(
  val generatedAt: String,
  val geoIp: GeoUpdateAsset,
  val geoSite: GeoUpdateAsset,
) {
  fun requireCompatibleWith(active: GeoUpdateManifest?) {
    if (active == null) return
    require(generatedAt >= active.generatedAt) { "Источник GEO-данных вернул устаревшую версию" }
    if (generatedAt == active.generatedAt) {
      require(geoIp == active.geoIp && geoSite == active.geoSite) {
        "GEO manifest изменился без новой версии"
      }
    }
  }

  companion object {
    private const val SCHEMA = 1
    private val GENERATED_AT_PATTERN = Regex("[0-9]{8}T[0-9]{6}Z")
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

    fun parse(payload: String, maxAssetBytes: Long): GeoUpdateManifest {
      val root = JSONObject(payload)
      require(root.getInt("schema") == SCHEMA) { "Неподдерживаемая версия GEO manifest" }
      val generatedAt = root.getString("generatedAt").also {
        require(it.matches(GENERATED_AT_PATTERN)) { "Некорректная версия GEO manifest" }
      }
      val assets = root.getJSONObject("assets")
      return GeoUpdateManifest(
        generatedAt = generatedAt,
        geoIp = assets.asset("geoip-ru.srs", maxAssetBytes),
        geoSite = assets.asset("geosite-category-ru.srs", maxAssetBytes),
      )
    }

    private fun JSONObject.asset(name: String, maxAssetBytes: Long): GeoUpdateAsset {
      val asset = getJSONObject(name)
      val sha256 = asset.getString("sha256").also {
        require(it.matches(SHA256_PATTERN)) { "Некорректный хэш GEO-файла" }
      }
      val size = asset.getLong("size").also {
        require(it in 1..maxAssetBytes) { "Некорректный размер GEO-файла" }
      }
      return GeoUpdateAsset(sha256, size)
    }
  }
}
