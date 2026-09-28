package com.example.veilark.profile

import kotlinx.coroutines.CancellationException

/** Tries each mirror as a complete manifest plus validated asset pair. */
internal object GeoUpdateCandidateSelector {
  fun select(
    urls: List<String>,
    active: GeoUpdateManifest?,
    loadManifest: (String) -> GeoUpdateManifest,
    validateCandidate: (GeoUpdateManifest) -> Unit,
  ): GeoUpdateManifest {
    var lastFailure: Throwable? = null
    for (url in urls) {
      try {
        val manifest = loadManifest(url)
        manifest.requireCompatibleWith(active)
        validateCandidate(manifest)
        return manifest
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        lastFailure = failure
      }
    }
    throw IllegalStateException("Не удалось загрузить проверяемую пару GEO-файлов", lastFailure)
  }
}
