package com.example.veilark.profile

import com.google.crypto.tink.subtle.X25519
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FairwayManagedEnrollmentTest {
  private val scope = "S".repeat(32)
  private val install = "I".repeat(24)
  private val secret = ByteArray(32) { (it + 1).toByte() }
  private val server = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
  private val claim = """{"schema":"veilark-core-device-claim-v1","binding_scope":"$scope"}"""

  private fun profile(publicHex: String, usedScope: String = scope): String =
    """{"schema":"veilark-core-device-profile-v1","binding_scope":"$usedScope","client_public_key_hex":"$publicHex","profile":{"schema":"veilark-argon-profile-v1","engine":"argon-v1","beta":true,"name":"Fairway","endpoint":{"ipv4":"192.0.2.1","port":443},"identity":{"server_static_public_base64":"$server"},"network":{"mtu":1360,"dns_ipv4":["1.1.1.1"]}}}"""

  @Test fun twoInstallationsUseIndependentKeysAndStableRetryRequests() {
    val requests = mutableListOf<String>()
    val identities = listOf(secret.copyOf(), ByteArray(32) { (it + 41).toByte() })
    try {
      identities.forEachIndexed { index, deviceSecret ->
        repeat(2) {
          val result = FairwayManagedEnrollment.enroll((if (index == 0) "A" else "W").repeat(24),
            { deviceSecret.copyOf() }) { body ->
            if (body == null) FairwayManagedEnrollment.Reply(200, true, claim.toByteArray()) else {
              val request = body.toString(Charsets.UTF_8); requests += request
              val public = JSONObject(request).getString("client_public_key_hex")
              FairwayManagedEnrollment.Reply(200, true, profile(public).toByteArray())
            }
          }
          try { CoreArgonProfile.parse(result).use { assertArrayEquals(deviceSecret, it.clientStaticPrivateCopy()) } }
          finally { result.fill(0) }
        }
      }
      assertEquals(requests[0], requests[1])
      assertEquals(requests[2], requests[3])
      assertNotEquals(JSONObject(requests[0]).getString("client_public_key_hex"), JSONObject(requests[2]).getString("client_public_key_hex"))
      assertNotEquals(JSONObject(requests[0]).getString("request_id"), JSONObject(requests[2]).getString("request_id"))
    } finally { identities.forEach { it.fill(0) } }
  }

  @Test fun retryUsesSamePublicIdentityAndNeverSendsPrivateKey() {
    val bodies = mutableListOf<String>()
    repeat(2) {
      val result = FairwayManagedEnrollment.enroll(install, { secret.copyOf() }) { body ->
        if (body == null) FairwayManagedEnrollment.Reply(200, true, claim.toByteArray())
        else {
          val request = body.toString(Charsets.UTF_8); bodies.add(request)
          val obj = JSONObject(request)
          assertEquals(setOf("schema", "binding_scope", "client_public_key_hex", "request_id"), obj.keys().asSequence().toSet())
          assertEquals(X25519.publicFromPrivate(secret).hex(), obj.getString("client_public_key_hex"))
          assertFalse(request.contains(Base64.getEncoder().encodeToString(secret)))
          FairwayManagedEnrollment.Reply(200, true, profile(obj.getString("client_public_key_hex")).toByteArray())
        }
      }
      try { CoreArgonProfile.parse(result).use { assertArrayEquals(secret, it.clientStaticPrivateCopy()) } }
      finally { result.fill(0) }
    }
    assertEquals(bodies[0], bodies[1])
  }

  @Test fun rejectsWrongScopeOrPrivateKeyFromServerAndWipesLocalCopy() {
    val publicHex = X25519.publicFromPrivate(secret).hex()
    val supplied = secret.copyOf()
    try {
      FairwayDeviceEnrollmentCodec.mergeProfile(profile(publicHex, "X".repeat(32)).toByteArray(), scope, publicHex, supplied)
      fail("Scope mismatch must fail")
    } catch (_: Exception) { assertArrayEquals(ByteArray(32), supplied) }
    val injected = profile(publicHex).replace("\"server_static_public_base64\":", "\"client_static_private_base64\":\"bad\",\"server_static_public_base64\":")
    try {
      FairwayDeviceEnrollmentCodec.mergeProfile(injected.toByteArray(), scope, publicHex, secret.copyOf())
      fail("Server must never supply a private key")
    } catch (_: Exception) { }
  }

  @Test fun pendingResponseDoesNotReturnAProfile() {
    var calls = 0
    try {
      FairwayManagedEnrollment.enroll(install, { secret.copyOf() }) {
        calls++
        if (calls == 1) FairwayManagedEnrollment.Reply(200, true, claim.toByteArray())
        else FairwayManagedEnrollment.Reply(202, true, ByteArray(0))
      }
      fail("Pending enrollment must not produce a profile")
    } catch (_: Exception) { assertEquals(2, calls) }
  }
  @Test fun pendingAndUnavailableRetryTheSameIdentity() {
    val requests = mutableListOf<String>()
    for (status in listOf(202, 503, 200)) {
      var calls = 0
      val operation = runCatching {
        FairwayManagedEnrollment.enroll(install, { secret.copyOf() }) { body ->
          calls++
          if (body == null) FairwayManagedEnrollment.Reply(200, true, claim.toByteArray())
          else {
            val request = body.toString(Charsets.UTF_8)
            requests.add(request)
            val pub = JSONObject(request).getString("client_public_key_hex")
            FairwayManagedEnrollment.Reply(status, true,
              if (status == 200) profile(pub).toByteArray() else ByteArray(0))
          }
        }
      }
      assertEquals(2, calls)
      if (status == 200) operation.getOrThrow().fill(0) else assertTrue(operation.isFailure)
    }
    assertEquals(1, requests.distinct().size)
  }

}

