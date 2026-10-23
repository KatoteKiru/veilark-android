package com.example.veilark.profile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Pure private-record engine. Encryption, durable commit and cross-instance locking
 * belong to Backend. No implicit profile selection, cache, network or raw import.
 */
internal class EnrollmentV2RecordStore(private val backend: Backend) {
  interface Backend {
    /** Serialize the complete action/commit per key across all instances.
     * read returns caller-owned bytes; write must copy before returning. A thrown
     * commit may have succeeded: retry reconciles against the durable record.
     */
    fun <T> transaction(recordKey: String, action: (Transaction) -> T): T
  }
  interface Transaction {
    fun read(): ByteArray?
    fun write(record: ByteArray)
  }
  enum class ImportResult { INSERTED, UPDATED, UNCHANGED, RESTORED }

  class StoredRecord internal constructor(
    val issuer: String, val keyId: String, val profileId: String,
    val revision: Long, val issuedAt: Long, val notBefore: Long, val expiresAt: Long,
    private val digest: ByteArray, private val profile: ByteArray,
  ) : AutoCloseable {
    private var closed = false
    internal val deleted get() = profile.isEmpty()
    internal fun sameDigest(other: ByteArray): Boolean = MessageDigest.isEqual(other, digest)
    internal fun tombstone(): StoredRecord = StoredRecord(issuer, keyId, profileId, revision,
      issuedAt, notBefore, expiresAt, digest.copyOf(), ByteArray(0))
    internal fun validate() {
      check(listOf(issuer, keyId, profileId).all { ID.matches(it) })
      check(listOf(revision, issuedAt, notBefore, expiresAt).all { it in 1..MAX_INTEGER })
      check(issuedAt <= notBefore && notBefore < expiresAt)
      check(expiresAt - issuedAt <= 2_592_000 && digest.size == 32)
      check(profile.size <= 16_384)
      if (!deleted) CoreArgonProfile.parse(profile).use {
        check(it.handshakeProfile == CoreArgonHandshakeProfile.LEGACY)
      }
    }
    internal fun encode(): ByteArray {
      val buffer = object : ByteArrayOutputStream() { fun erase() { buf.fill(0) } }
      try { DataOutputStream(buffer).use { output ->
        output.writeInt(MAGIC); output.writeByte(1)
        for (id in listOf(issuer, keyId, profileId)) { output.writeByte(id.length); output.writeBytes(id) }
        output.writeLong(revision); output.writeLong(issuedAt)
        output.writeLong(notBefore); output.writeLong(expiresAt)
        output.write(digest); output.writeInt(profile.size); output.write(profile)
      }
        return buffer.toByteArray()
      } finally { buffer.erase() }
    }
    @Synchronized fun profileCopy(): ByteArray { check(!closed) { ERROR }; return profile.copyOf() }
    @Synchronized fun signedContentDigestCopy(): ByteArray { check(!closed) { ERROR }; return digest.copyOf() }
    @Synchronized override fun close() { profile.fill(0); digest.fill(0); closed = true }
    override fun toString(): String = "EnrollmentV2RecordStore.StoredRecord(secrets redacted)"
  }

  /** Accepts verifier-created provenance only. No caller metadata overrides. */
  fun import(verified: EnrollmentV2DraftConsumer, nowEpochSeconds: Long): ImportResult =
    import(verified) { nowEpochSeconds }

  /** Evaluate time under the transaction lock, after the authoritative read. */
  fun import(verified: EnrollmentV2DraftConsumer, nowEpochSeconds: () -> Long): ImportResult = bounded {
    val profile = verified.profileCopy()
    var digest: ByteArray? = null
    try {
      val signedDigest = verified.signedContentDigestCopy()
      digest = signedDigest
      val candidate = StoredRecord(verified.issuer, verified.keyId, verified.profileId,
        verified.revision, verified.issuedAt, verified.notBefore, verified.expiresAt, signedDigest, profile)
      candidate.validate()
      backend.transaction(recordKey(candidate.issuer, candidate.profileId)) { tx ->
        val previous = read(tx, candidate.issuer, candidate.profileId)
        try {
          val now = nowEpochSeconds()
          check(now in 1..MAX_INTEGER && now < candidate.expiresAt)
          check(candidate.issuedAt <= now + 300 && candidate.notBefore <= now + 300)
          val result = when {
            previous == null -> ImportResult.INSERTED
            candidate.revision < previous.revision -> error(ERROR)
            candidate.revision > previous.revision -> ImportResult.UPDATED
            !previous.sameDigest(signedDigest) -> error(ERROR)
            previous.deleted -> ImportResult.RESTORED
            else -> ImportResult.UNCHANGED
          }
          if (result != ImportResult.UNCHANGED) write(tx, candidate)
          result
        } finally { previous?.close() }
      }
    } finally { profile.fill(0); digest?.fill(0) }
  }

  /** Load does not apply import-window expiry to already imported VPN credentials. */
  fun load(issuer: String, profileId: String): StoredRecord? = bounded {
    backend.transaction(recordKey(issuer, profileId)) { tx ->
      val record = read(tx, issuer, profileId)
      if (record?.deleted == true) { record.close(); null } else record
    }
  }

  /** Retains provenance/digest/revision floor in the same atomic record. */
  fun delete(issuer: String, profileId: String): Boolean = bounded {
    backend.transaction(recordKey(issuer, profileId)) { tx ->
      val record = read(tx, issuer, profileId) ?: return@transaction false
      try {
        if (record.deleted) false else {
          val tombstone = record.tombstone()
          try { write(tx, tombstone) } finally { tombstone.close() }
          true
        }
      } finally { record.close() }
    }
  }

  private fun recordKey(issuer: String, profileId: String): String {
    check(ID.matches(issuer) && ID.matches(profileId))
    val identity = byteArrayOf(issuer.length.toByte()) + issuer.toByteArray(Charsets.US_ASCII) +
      byteArrayOf(profileId.length.toByte()) + profileId.toByteArray(Charsets.US_ASCII)
    return try {
      "veilark-enrollment-v2-" + MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it.toInt() and 255) }
    } finally { identity.fill(0) }
  }

  private fun read(tx: Transaction, issuer: String, profileId: String): StoredRecord? {
    val bytes = tx.read() ?: return null
    var record: StoredRecord? = null
    var digest: ByteArray? = null
    var profile: ByteArray? = null
    try {
      check(bytes.size in 1..MAX_RECORD)
      val input = DataInputStream(ByteArrayInputStream(bytes))
      check(input.readInt() == MAGIC && input.readUnsignedByte() == 1)
      val iss = readId(input); val kid = readId(input); val pid = readId(input)
      val rev = input.readLong(); val iat = input.readLong(); val nbf = input.readLong(); val exp = input.readLong()
      val digestBytes = ByteArray(32).also { digest = it; input.readFully(it) }
      val length = input.readInt().also { check(it in 0..16_384 && it == input.available()) }
      val profileBytes = ByteArray(length).also { profile = it; input.readFully(it) }
      check(input.available() == 0 && iss == issuer && pid == profileId)
      val parsed = StoredRecord(iss, kid, pid, rev, iat, nbf, exp, digestBytes, profileBytes)
      record = parsed
      parsed.validate()
      return parsed.also { record = null; digest = null; profile = null }
    } finally { bytes.fill(0); record?.close(); digest?.fill(0); profile?.fill(0) }
  }

  private fun write(tx: Transaction, record: StoredRecord) {
    val bytes = record.encode()
    try { check(bytes.size <= MAX_RECORD); tx.write(bytes) } finally { bytes.fill(0) }
  }

  private fun readId(input: DataInputStream): String {
    val length = input.readUnsignedByte().also { check(it in 1..48) }
    val bytes = ByteArray(length)
    return try {
      input.readFully(bytes)
      check(bytes.all { it.toInt() in 0..127 })
      String(bytes, Charsets.US_ASCII).also { check(ID.matches(it)) }
    } finally { bytes.fill(0) }
  }

  private inline fun <T> bounded(action: () -> T): T = try { action() }
    catch (_: Exception) { throw IllegalArgumentException(ERROR) }

  companion object {
    private const val ERROR = "Invalid enrollment record operation."
    private const val MAX_INTEGER = 9_007_199_254_740_991L
    private const val MAX_RECORD = 20 * 1024
    private const val MAGIC = 0x56455232 // VER2; private storage, not enrollment wire bytes.
    private val ID = Regex("[A-Za-z0-9_-]{1,48}")
  }
}
