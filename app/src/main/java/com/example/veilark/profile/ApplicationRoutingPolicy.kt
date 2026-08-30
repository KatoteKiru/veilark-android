package com.example.veilark.profile

enum class ApplicationRoutingMode(val preferenceValue: String) {
  All(ProfileSelection.APPS_ALL),
  Only(ProfileSelection.APPS_ONLY),
  Bypass(ProfileSelection.APPS_BYPASS),
  ;

  companion object {
    fun fromPreference(value: String): ApplicationRoutingMode =
      entries.firstOrNull { it.preferenceValue == value }
        ?: throw IllegalArgumentException("Неизвестный режим маршрутизации приложений")
  }
}

data class ApplicationRoutingPolicy(
  val mode: ApplicationRoutingMode,
  val selectedPackages: Set<String>,
) {
  init {
    require(mode == ApplicationRoutingMode.All || selectedPackages.isNotEmpty()) {
      "Выберите хотя бы одно приложение"
    }
  }

  fun forTrustTunnel(vpnPackage: String): TrustTunnelApplicationRules {
    require(vpnPackage.isNotBlank()) { "Не задан пакет VPN-приложения" }
    val selected = selectedPackages
      .asSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .toSortedSet()

    return when (mode) {
      ApplicationRoutingMode.All -> TrustTunnelApplicationRules(
        includedPackages = emptySet(),
        excludedPackages = setOf(vpnPackage),
      )

      ApplicationRoutingMode.Only -> {
        val included = selected - vpnPackage
        require(included.isNotEmpty()) { "Выберите хотя бы одно приложение кроме Veilark" }
        TrustTunnelApplicationRules(
          includedPackages = included,
          excludedPackages = emptySet(),
        )
      }

      ApplicationRoutingMode.Bypass -> TrustTunnelApplicationRules(
        includedPackages = emptySet(),
        excludedPackages = selected + vpnPackage,
      )
    }
  }

  companion object {
    fun fromPreferences(mode: String, selectedPackages: Set<String>) =
      ApplicationRoutingPolicy(
        mode = ApplicationRoutingMode.fromPreference(mode),
        selectedPackages = selectedPackages,
      )
  }
}

data class TrustTunnelApplicationRules(
  val includedPackages: Set<String>,
  val excludedPackages: Set<String>,
) {
  init {
    require(includedPackages.isEmpty() || excludedPackages.isEmpty()) {
      "Списки разрешённых и исключённых приложений TrustTunnel несовместимы"
    }
  }
}
