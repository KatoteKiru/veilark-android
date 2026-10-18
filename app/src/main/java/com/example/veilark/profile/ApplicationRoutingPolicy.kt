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

  fun forTrustTunnel(vpnPackage: String): TrustTunnelApplicationRules = forAndroidVpn(vpnPackage)

  /** Shared Android allow/disallow plan, independent of the tunnel transport. */
  fun forAndroidVpn(vpnPackage: String): TrustTunnelApplicationRules {
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

  /** Callbacks return false ONLY for a known missing package, not arbitrary errors.
   * Apply to the actual Builder to avoid a package-removal race after preflight.
   * Keep the saved selection unchanged; an empty effective Only list never becomes All.
   */
  fun applyInstalled(
    vpnPackage: String,
    addIncluded: (String) -> Boolean,
    addExcluded: (String) -> Boolean,
  ): AppliedApplicationRules {
    require(vpnPackage.isNotBlank())
    require(vpnPackage !in includedPackages)
    require(includedPackages.isNotEmpty() || vpnPackage in excludedPackages)
    var included = 0
    var excluded = 0
    var missing = 0
    includedPackages.forEach {
      if (addIncluded(it)) included++ else missing++
    }
    if (includedPackages.isNotEmpty() && included == 0) throw ApplicationRoutingUnavailableException()
    excludedPackages.forEach {
      if (addExcluded(it)) excluded++
      else {
        check(it != vpnPackage) { "VPN application routing could not be configured" }
        missing++
      }
    }
    return AppliedApplicationRules(included, excluded, missing)
  }
}

class ApplicationRoutingUnavailableException : IllegalArgumentException(
  "Selected applications are unavailable. Select an installed application for Only mode.",
)

data class AppliedApplicationRules(val acceptedIncluded: Int, val acceptedExcluded: Int, val missing: Int)
