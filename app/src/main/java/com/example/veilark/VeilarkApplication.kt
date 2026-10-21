package com.example.veilark

import android.app.Application
import com.example.veilark.protocol.TrustTunnelManager
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.protocol.BuiltInTrustProfileMigration
import com.example.veilark.profile.NetworkProfileMigration
import com.example.veilark.profile.SecureProfileStore

class VeilarkApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    // The periodic manifest checker does not need native engines or profile migrations.
    if (Application.getProcessName().endsWith(":updates")) return
    runCatching { com.example.veilark.update.UpdateNoticeJob.schedule(this) }
    TechnicalLogStore.initialize(this)
    LibboxRuntime.install(this)
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
  }
}
