package com.example.veilark.profile

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Persisted profile choice is independent of engine activation. */
enum class CoreProfileKind(val preferenceValue: String) {
  LEGACY_V1("legacy_v1"),
  ARGON_V1("argon_v1");

  companion object {
    private const val ERROR = "Invalid Veilark Core profile."
    private const val CHOICE_ERROR = "Invalid Veilark Core profile selection."
    private const val MAX_BYTES = 16 * 1024

    /** Missing explicit choices never fall back to a different identity. */
    fun resolve(saved: Any?, legacyExists: Boolean, argonExists: Boolean): CoreProfileKind? {
      val choice = when (saved) {
        null -> if (legacyExists) LEGACY_V1 else if (argonExists) ARGON_V1 else null
        LEGACY_V1.preferenceValue -> LEGACY_V1
        ARGON_V1.preferenceValue -> ARGON_V1
        else -> throw IllegalArgumentException(CHOICE_ERROR)
      }
      return when (choice) {
        LEGACY_V1 -> if (legacyExists) choice else null
        ARGON_V1 -> if (argonExists) choice else null
        null -> null
      }
    }

    /** Dispatch only by the exact top-level schema after strict, bounded JSON parsing.
     * The chosen store still validates the complete corresponding semantic schema.
     */
    fun recognize(payload: ByteArray): CoreProfileKind {
      try {
        check(payload.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(payload)).toString()
        val root = CoreProfileJson.parse(text) as? Map<*, *> ?: error("invalid")
        return when (root["schema"]) {
          "veilark-core-beta-profile-v1" -> LEGACY_V1
          "veilark-argon-profile-v1", "veilark-argon-profile-v2" -> ARGON_V1
          else -> throw IllegalArgumentException(ERROR)
        }
      } catch (_: Exception) {
        throw IllegalArgumentException(ERROR)
      }
    }
  }
}
