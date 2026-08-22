package com.example.veilark.profile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(
  val packageName: String,
  val label: String,
)

object InstalledAppLoader {
  private const val ICON_CACHE_BYTES = 4 * 1024 * 1024
  private val iconCache = object : LruCache<String, Bitmap>(ICON_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
  }

  fun load(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launcherActivities = packageManager
      .queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
    return launcherActivities
      .asSequence()
      .mapNotNull { info ->
        val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
        if (packageName == context.packageName) return@mapNotNull null
        InstalledApp(
          packageName = packageName,
          label = info.loadLabel(packageManager).toString().ifBlank { packageName },
        )
      }
      .distinctBy(InstalledApp::packageName)
      .sortedBy { it.label.lowercase() }
      .toList()
  }

  fun loadIcon(context: Context, packageName: String): Bitmap? {
    synchronized(iconCache) {
      iconCache.get(packageName)?.let { return it }
    }
    val iconSize = (48 * context.resources.displayMetrics.density).toInt()
      .coerceAtLeast(48)
    val icon = runCatching {
      context.packageManager.getApplicationIcon(packageName).toBitmap(
        width = iconSize,
        height = iconSize,
        config = Bitmap.Config.ARGB_8888,
      )
    }.getOrNull() ?: return null
    synchronized(iconCache) {
      iconCache.put(packageName, icon)
    }
    return icon
  }
}
