package com.example.veilark.profile

import android.content.Context
import com.example.veilark.BuildConfig
import com.example.veilark.io.readAtMost
import com.google.crypto.tink.subtle.X25519
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Explicit opt-in; ordinary subscription imports and existing Core profiles stay separate. */
internal object FairwayManagedEnrollment {
  internal data class Reply(val status: Int, val noStore: Boolean, val body: ByteArray)

  suspend fun fetch(context: Context, source: String): ByteArray = withContext(Dispatchers.IO) {
    val headers = SubscriptionFetcher.androidHeaders(context, source)
    try {
      require(SubscriptionClientObservation.isControlled(source, BuildConfig.SUBSCRIPTION_HOST, BuildConfig.SUBSCRIPTION_PORT))
      val install = checkNotNull(headers["X-Veilark-Install-Id"])
      enroll(install, { scope -> FairwayDeviceKeyStore.loadOrCreate(context, scope, install) }) { body ->
        request(source, headers, body)
      }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw IllegalArgumentException("Не удалось загрузить подписку Fairway") }
  }

  internal fun enroll(install: String, key: (String) -> ByteArray, send: (ByteArray?) -> Reply): ByteArray {
    require(install.matches(Regex("[A-Za-z0-9_-]{24}")))
    val claim = send(null)
    val scope = try { requireOk(claim); FairwayDeviceEnrollmentCodec.claimScope(claim.body) }
      finally { claim.body.fill(0) }
    val secret = key(scope)
    try {
      require(secret.size == 32)
      val publicHex = X25519.publicFromPrivate(secret).hex()
      val requestId = MessageDigest.getInstance("SHA-256").digest(
        "veilark-core-device-enroll-v1\u0000$scope\u0000$publicHex".toByteArray(Charsets.UTF_8)).hex()
      val body = JSONObject().put("schema", "veilark-core-device-enroll-v1")
        .put("binding_scope", scope).put("client_public_key_hex", publicHex)
        .put("request_id", requestId).toString().toByteArray(Charsets.UTF_8)
      val reply = try { send(body) } finally { body.fill(0) }
      try {
        requireOk(reply)
        return FairwayDeviceEnrollmentCodec.mergeProfile(reply.body, scope, publicHex, secret.copyOf())
      } finally { reply.body.fill(0) }
    } finally { secret.fill(0) }
  }

  private fun requireOk(reply: Reply) {
    check(reply.status == 200 && reply.noStore && reply.body.size in 1..65_536)
  }

  private fun request(source: String, headers: Map<String, String>, body: ByteArray?): Reply {
    val connection = URL(source).openConnection() as HttpsURLConnection
    try {
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 10_000
      connection.readTimeout = 20_000
      connection.setRequestProperty("X-Veilark-Core-Enrollment", "1")
      headers.forEach(connection::setRequestProperty)
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("Accept-Encoding", "identity")
      connection.setRequestProperty("Cache-Control", "no-store")
      if (body != null) {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
      }
      val status = connection.responseCode
      val noStore = connection.getHeaderFields().entries
        .filter { it.key.equals("Cache-Control", true) }.flatMap { it.value ?: emptyList() }
        .flatMap { it.split(',') }.any { it.trim().equals("no-store", true) }
      val response = if (status == 200) connection.inputStream.use { it.readAtMost(65_537) } else ByteArray(0)
      return Reply(status, noStore, response)
    } finally { connection.disconnect() }
  }
}
