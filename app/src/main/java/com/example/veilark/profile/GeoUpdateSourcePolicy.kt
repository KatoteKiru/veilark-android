package com.example.veilark.profile

import java.net.URI

internal object GeoUpdateSourcePolicy {
  fun requireAllowed(url: String): URI {
    val uri = URI(url)
    require(uri.scheme == "https") { "Недопустимый источник геоданных" }
    require(
      (uri.host == "sub.senyasenyavski.uk" && uri.port == -1) ||
        (uri.host == "nl2.senyasenyavski.uk" && uri.port == 2096) ||
        (uri.host == "raw.githubusercontent.com" && uri.port == -1),
    ) { "Недопустимый источник геоданных" }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
      "Недопустимый источник геоданных"
    }
    return uri
  }
}
