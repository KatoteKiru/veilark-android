package com.example.veilark.profile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(
  val packageName: String,
  val label: String,
  val icon: Bitmap?,
)

object InstalledAppLoader {
  fun load(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launcherActivities = packageManager
      .queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
    val iconSize = (48 * context.resources.displayMetrics.density).toInt()
      .coerceAtLeast(48)
    return launcherActivities
      .asSequence()
      .mapNotNull { info ->
        val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
        if (packageName == context.packageName) return@mapNotNull null
        InstalledApp(
          packageName = packageName,
          label = info.loadLabel(packageManager).toString().ifBlank { packageName },
          icon = runCatching {
            info.loadIcon(packageManager).toBitmap(
              width = iconSize,
              height = iconSize,
              config = Bitmap.Config.ARGB_8888,
            )
          }.getOrNull(),
        )
      }
      .distinctBy(InstalledApp::packageName)
      .sortedBy { it.label.lowercase() }
      .toList()
  }
}
