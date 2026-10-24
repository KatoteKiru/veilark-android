package com.example.veilark.profile

import android.content.Context
import com.example.veilark.BuildConfig

/** The relay route's own encrypted slot, next to and independent of CoreArgonProfileStore. */
object CoreArgonRelayProfileStore {
  private const val ID = SecureProfileStore.CORE_ARGON_RELAY

  /** Static lab credentials pass only where the caller vouches for the source: a debug
   * build, or an enroll/v2 payload signed by our issuer. */
  fun import(
    context: Context, payload: ByteArray, allowStaticCredentials: Boolean = BuildConfig.DEBUG,
  ): CoreArgonRelayProfile {
    val checked = CoreArgonRelayProfile.parse(payload, allowStaticCredentials)
    try {
      SecureProfileStore.save(context, ID, checked.validatedEnvelope())
      checked.discardEnvelope()
      return checked
    } catch (failure: Throwable) {
      checked.close()
      throw failure
    }
  }

  // The import gate above is the policy; the slot is authenticated encryption under the
  // Keystore, so whatever it holds already passed that gate.
  fun load(context: Context): CoreArgonRelayProfile =
    CoreArgonRelayProfile.parse(SecureProfileStore.load(context, ID), true).also { it.discardEnvelope() }

  fun exists(context: Context): Boolean = SecureProfileStore.exists(context, ID)
  fun delete(context: Context): Boolean = SecureProfileStore.delete(context, ID)
}
