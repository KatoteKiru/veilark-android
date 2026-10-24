package com.example.veilark.profile

import android.content.Context
import com.example.veilark.BuildConfig

/** The relay route's own encrypted slot, next to and independent of CoreArgonProfileStore. */
object CoreArgonRelayProfileStore {
  private const val ID = SecureProfileStore.CORE_ARGON_RELAY

  fun import(context: Context, payload: ByteArray): CoreArgonRelayProfile {
    val checked = CoreArgonRelayProfile.parse(payload, BuildConfig.DEBUG)
    try {
      SecureProfileStore.save(context, ID, checked.validatedEnvelope())
      checked.discardEnvelope()
      return checked
    } catch (failure: Throwable) {
      checked.close()
      throw failure
    }
  }

  fun load(context: Context): CoreArgonRelayProfile =
    CoreArgonRelayProfile.parse(SecureProfileStore.load(context, ID), BuildConfig.DEBUG).also { it.discardEnvelope() }

  fun exists(context: Context): Boolean = SecureProfileStore.exists(context, ID)
  fun delete(context: Context): Boolean = SecureProfileStore.delete(context, ID)
}
