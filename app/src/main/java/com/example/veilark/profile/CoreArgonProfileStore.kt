package com.example.veilark.profile

import android.content.Context

/** Validates before atomic encrypted replacement. Legacy profiles are separate;
 * importing configuration never authenticates a session or selects an engine.
 */
object CoreArgonProfileStore {
  private const val ID = SecureProfileStore.CORE_ARGON

  fun import(context: Context, payload: ByteArray): CoreArgonProfile {
    val checked = CoreArgonProfile.parse(payload)
    try {
      SecureProfileStore.save(context, ID, checked.validatedEnvelope())
      checked.discardEnvelope()
      return checked
    } catch (failure: Throwable) {
      checked.close()
      throw failure
    }
  }

  fun load(context: Context): CoreArgonProfile =
    CoreArgonProfile.parse(SecureProfileStore.load(context, ID)).also { it.discardEnvelope() }

  fun exists(context: Context): Boolean = SecureProfileStore.exists(context, ID)
  fun delete(context: Context): Boolean = SecureProfileStore.delete(context, ID)
}
