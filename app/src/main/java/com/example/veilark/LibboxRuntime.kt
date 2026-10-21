package com.example.veilark

import android.content.Context
import com.example.veilark.diagnostics.TechnicalLogStore
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.Locale

/** Register context at app startup; load this independent engine only on demand. */
internal object LibboxRuntime {
  @Volatile private var context: Context? = null
  private val gate = NativeInitializationGate {
    val app = checkNotNull(context) { "Native context not registered" }
    try {
      Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))
      Libbox.setup(SetupOptions().apply {
        basePath = app.filesDir.path
        workingPath = app.getExternalFilesDir(null)?.path ?: app.filesDir.path
        tempPath = app.cacheDir.path
        logMaxLines = 1000
        debug = BuildConfig.DEBUG
      })
      if (BuildConfig.DEBUG) Libbox.redirectStderr(File(app.cacheDir, "libbox-stderr.log").path)
    } catch (error: Throwable) {
      NativeRuntimeState.recordLibbox(error)
      TechnicalLogStore.error("NATIVE", "sing-box initialization failed: ${error.javaClass.simpleName}")
      throw error
    }
  }

  @Synchronized fun install(app: Context) {
    val application = app.applicationContext
    check(context == null || context === application)
    context = application
  }

  fun requireReady() {
    NativeRuntimeState.requireLibbox()
    gate.requireReady()
  }

  fun checkConfig(config: String) {
    requireReady()
    Libbox.checkConfig(config)
  }
}
