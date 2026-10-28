package com.example.veilark.profile

import android.content.Context
import com.example.veilark.BuildConfig
import org.json.JSONObject
import java.security.MessageDigest

/** Separate from the imported static identity. The bearer source is encrypted with the profile. */
internal object FairwayManagedProfileStore {
  fun exists(context: Context, id: String): Boolean = SecureProfileStore.exists(context, id)

  internal fun identity(source: String): String = "veilark-managed-fairway-" + MessageDigest.getInstance("SHA-256")
    .digest(("veilark-managed-fairway-source-v1\u0000" + source).toByteArray(Charsets.UTF_8)).hex()

  fun import(context: Context, source: String, profile: ByteArray): CoreProfileMetadata {
    requireControlled(source)
    val metadata = CoreArgonProfile.parse(profile).use {
      CoreProfileMetadata(CoreProfileKind.ARGON_V1, it.name, it.dnsIpv4, it.handshakeProfile)
    }
    val record = JSONObject().put("schema", "veilark-managed-fairway-local-v1")
      .put("source", source).put("profile", JSONObject(profile.toString(Charsets.UTF_8)))
    SecureProfileStore.save(context, identity(source), record.toString())
    return metadata
  }

  fun load(context: Context, id: String): CoreArgonProfile {
    val record = record(context, id)
    val bytes = record.getJSONObject("profile").toString().toByteArray(Charsets.UTF_8)
    return try { CoreArgonProfile.parse(bytes).also { it.discardEnvelope() } } finally { bytes.fill(0) }
  }

  fun source(context: Context, id: String): String = record(context, id).getString("source")

  fun delete(context: Context, id: String): Boolean = SecureProfileStore.delete(context, id)

  private fun record(context: Context, id: String): JSONObject = decode(SecureProfileStore.load(context, id))
    .also { check(identity(it.getString("source")) == id) }

  internal fun decode(text: String): JSONObject {
    val record = JSONObject(text)
    require(record.keys().asSequence().toSet() == setOf("schema", "source", "profile") &&
      record.getString("schema") == "veilark-managed-fairway-local-v1")
    requireControlled(record.getString("source"))
    val bytes = record.getJSONObject("profile").toString().toByteArray(Charsets.UTF_8)
    try { CoreArgonProfile.parse(bytes).close() } finally { bytes.fill(0) }
    return record
  }

  private fun requireControlled(source: String) {
    require(SubscriptionClientObservation.isControlled(source, BuildConfig.SUBSCRIPTION_HOST, BuildConfig.SUBSCRIPTION_PORT))
  }
}
