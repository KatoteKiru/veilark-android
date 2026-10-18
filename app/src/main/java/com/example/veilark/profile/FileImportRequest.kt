package com.example.veilark.profile

import java.net.URI

/** External document imports require an explicitly granted provider URI, never local paths. */
internal object FileImportRequest {
  fun accepts(action: String?, rawUri: String, hasReadGrant: Boolean): Boolean {
    if (!hasReadGrant || action !in setOf("android.intent.action.VIEW", "android.intent.action.SEND") ||
      rawUri.length > 8192 || rawUri.any { it.isISOControl() }) return false
    val uri = runCatching { URI(rawUri) }.getOrNull() ?: return false
    return uri.scheme.equals("content", ignoreCase = true) &&
      !uri.rawAuthority.isNullOrBlank() && uri.rawUserInfo == null && uri.port == -1 &&
      uri.rawFragment == null
  }
}
