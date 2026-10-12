package com.example.veilark.profile

import android.content.Context

/** Validates before encrypted atomic replacement. Importing never activates a VPN engine. */
object CoreBetaProfileStore {
  private const val ID = SecureProfileStore.CORE_BETA

  fun import(context: Context, payload: ByteArray): CoreBetaProfile {
    val checked = CoreBetaProfile.parse(payload)
    SecureProfileStore.save(context, ID, checked.validatedEnvelope())
    return checked
  }

  fun load(context: Context): CoreBetaProfile =
    CoreBetaProfile.parse(SecureProfileStore.load(context, ID))

  fun exists(context: Context): Boolean = SecureProfileStore.exists(context, ID)
  fun delete(context: Context): Boolean = SecureProfileStore.delete(context, ID)
}
