package com.example.veilark

import android.app.Application
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import com.example.veilark.protocol.TrustTunnelManager
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.protocol.BuiltInTrustProfileMigration
import com.example.veilark.profile.NetworkProfileMigration
import com.example.veilark.profile.SecureProfileStore
import java.io.File
import java.util.Locale

class VeilarkApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    TechnicalLogStore.initialize(this)
    SecureProfileStore.migrateLegacy(this)
    BuiltInTrustProfileMigration.migrate(this)
    runCatching { NetworkProfileMigration.migrateStored(this) }
    // Preserve custom routes on upgrade. Earlier emergency recovery builds
    // cleared them once; current migrations reconcile valid settings in place.
    runCatching { NetworkProfileMigration.reconcileStoredSettings(this) }
    runCatching {
      TrustTunnelManager.initialize(this)
    }.onFailure {
      NativeRuntimeState.recordTrustTunnel(it)
      TechnicalLogStore.error(
        "NATIVE",
        "TrustTunnel initialization failed: ${it.javaClass.simpleName}",
      )
    }
    runCatching {
      Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))
      Libbox.setup(
        SetupOptions().apply {
          basePath = filesDir.path
          workingPath = getExternalFilesDir(null)?.path ?: filesDir.path
          tempPath = cacheDir.path
          logMaxLines = 1_000
          debug = BuildConfig.DEBUG
        },
      )
      if (BuildConfig.DEBUG) {
        Libbox.redirectStderr(File(cacheDir, "libbox-stderr.log").path)
      }
    }.onFailure {
      NativeRuntimeState.recordLibbox(it)
      TechnicalLogStore.error(
        "NATIVE",
        "sing-box initialization failed: ${it.javaClass.simpleName}",
      )
    }
  }
}
