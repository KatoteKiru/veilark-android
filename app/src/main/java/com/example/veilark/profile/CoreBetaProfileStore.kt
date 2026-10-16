package com.example.veilark.profile

import android.content.Context

/** Validates before encrypted atomic replacement. Importing never activates a VPN engine. */
object CoreBetaProfileStore {
  private const val ID = SecureProfileStore.CORE_BETA

  fun import(context: Context, payload: ByteArray): CoreBetaProfile {
    val checked = CoreBetaProfile.parse(payload)
    try {
      SecureProfileStore.save(context, ID, checked.validatedEnvelope())
      checked.discardEnvelope()
      return checked
    } catch (failure: Throwable) {
      checked.close()
      throw failure
    }
  }

  fun load(context: Context): CoreBetaProfile =
    CoreBetaProfile.parse(SecureProfileStore.load(context, ID)).also { it.discardEnvelope() }

  fun exists(context: Context): Boolean = SecureProfileStore.exists(context, ID)
  fun delete(context: Context): Boolean = SecureProfileStore.delete(context, ID)
}
