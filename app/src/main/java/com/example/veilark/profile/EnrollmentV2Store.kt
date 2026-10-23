package com.example.veilark.profile

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Candidate release trust boundary. No caller-supplied key map or verified flag.
 * Only a separately authenticated app release may populate this registry; NOT remote data.
 * The stopped-state import manager calls this facade. Import does not switch the
 * engine, start VPN, or automatically migrate an existing raw profile.
 */
internal object EnrollmentV2Store {
  private const val ERROR = "Protected enrollment is unavailable."
  // Dedicated issuer, created under pinned SSH with root-only custody. Private key
  // never enters this repository or APK. Rotation requires an authenticated app release.
  private val releaseIssuerKeys: Map<Pair<String, String>, ByteArray> = mapOf(
    ("veilark-core" to "enrollment-20261023-01") to Base64.getDecoder().decode(
      "MCowBQYDK2VwAyEARP69gvw/EZJASqhvG9OQkiwpS+LJYlP+EbHjMXXY2fw="),
  )

  fun isCanonicalLink(link: String): Boolean = EnrollmentV2Fetcher.isCanonicalLink(link)

  /** Explicit user import selects the Core source, not the engine and never starts VPN. */
  suspend fun importSelectedLink(context: Context, link: String): CoreProfileMetadata {
    check(releaseIssuerKeys.isNotEmpty()) { ERROR }
    val ciphertext = EnrollmentV2Fetcher.fetch(link)
    try {
      return withContext(Dispatchers.IO) {
        EnrollmentV2DraftConsumer.verify(link, ciphertext, releaseIssuerKeys, epochSeconds()).use { verified ->
          store(context).import(verified, ::epochSeconds)
          CoreProfileStore.selectEnrollment(context, verified.issuer, verified.profileId)
        }
      }
    } finally { ciphertext.fill(0) }
  }

  /** Dormant import pipeline. Reject before network IO until release trust is installed.
   * The delivery key stays in [link]; only the locator reaches the fixed-origin fetcher.
   */
  suspend fun importLink(context: Context, link: String): EnrollmentV2RecordStore.ImportResult {
    check(releaseIssuerKeys.isNotEmpty()) { ERROR }
    val ciphertext = EnrollmentV2Fetcher.fetch(link)
    try { return import(context, link, ciphertext) }
    finally { ciphertext.fill(0) }
  }

  suspend fun import(context: Context, link: String, ciphertext: ByteArray): EnrollmentV2RecordStore.ImportResult =
    withContext(Dispatchers.IO) {
      check(releaseIssuerKeys.isNotEmpty()) { ERROR }
      EnrollmentV2DraftConsumer.verify(link, ciphertext, releaseIssuerKeys, epochSeconds()).use {
        store(context).import(it, ::epochSeconds)
      }
    }

  suspend fun load(context: Context, issuer: String, profileId: String): EnrollmentV2RecordStore.StoredRecord? =
    withContext(Dispatchers.IO) { loadBlocking(context, issuer, profileId) }

  /** Service/metadata callers already use the existing profile IO boundary. */
  internal fun loadBlocking(context: Context, issuer: String, profileId: String): EnrollmentV2RecordStore.StoredRecord? {
      check(releaseIssuerKeys.isNotEmpty()) { ERROR }
      val value = store(context).load(issuer, profileId) ?: return null
      if (!releaseIssuerKeys.containsKey(value.issuer to value.keyId)) {
        value.close()
        error(ERROR)
      }
      return value
  }

  internal fun deleteBlocking(context: Context, issuer: String, profileId: String): Boolean =
    store(context).delete(issuer, profileId)

  /** UI deletion affordance only. Never authorizes metadata preflight or connection. */
  internal fun hasLocalRecord(context: Context, issuer: String, profileId: String): Boolean =
    store(context).load(issuer, profileId)?.use { true } ?: false

  /** Deletion retains the encrypted revision floor even if its issuer is no longer trusted. */
  suspend fun delete(context: Context, issuer: String, profileId: String): Boolean =
    withContext(Dispatchers.IO) { store(context).delete(issuer, profileId) }

  private fun store(context: Context) = EnrollmentV2RecordStore(AndroidBackend(context.applicationContext))
  private fun epochSeconds(): Long = System.currentTimeMillis() / 1000

  /** Lock covers read, policy decision, encryption and atomic replacement. Both process
   * and OS locking matter: AtomicFile itself does not serialize multiple writers.
   * One common lock also serializes first creation of the dedicated enrollment key.
   * Imports are occasional IO work; this never participates in packet forwarding.
   */
  // Storage primitive only, NOT issuer authority. Production imports go through the
  // facade above; module visibility allows real platform tests without key-map overrides.
  internal class AndroidBackend(private val context: Context) : EnrollmentV2RecordStore.Backend {
    override fun <T> transaction(recordKey: String, action: (EnrollmentV2RecordStore.Transaction) -> T): T =
      synchronized(processLock) {
        val destination = SecureProfileStore.profileFile(context.noBackupFilesDir, recordKey)
        val directory = requireNotNull(destination.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { ERROR }
        RandomAccessFile(File(directory, "enrollment-v2-store.lock"), "rw").use { file ->
          file.channel.use { channel ->
            // Fail busy instead of waiting indefinitely on a different process.
            val lock = channel.tryLock() ?: error(ERROR)
            lock.use {
              action(object : EnrollmentV2RecordStore.Transaction {
                override fun read(): ByteArray? {
                  val backup = File(destination.path + ".bak")
                  val pending = File(destination.path + ".new")
                  for (candidate in listOf(destination, backup, pending)) {
                    check(!candidate.exists() || (candidate.isFile && candidate.length() <= MAX_ENCRYPTED_BYTES)) { ERROR }
                  }
                  // An uncommitted initial .new is not a durable revision floor.
                  if (!destination.exists() && !backup.exists()) return null
                  return SecureProfileStore.loadEnrollmentBytes(context, recordKey).also {
                    if (it.size > MAX_RECORD_BYTES) { it.fill(0); error(ERROR) }
                  }
                }

                override fun write(record: ByteArray) {
                  check(record.size in 1..MAX_RECORD_BYTES) { ERROR }
                  SecureProfileStore.saveEnrollmentBytes(context, recordKey, record)
                  // A thrown readback may follow a committed write. The engine always
                  // rereads on retry and must never assume exception == rollback.
                  val readback = read() ?: error(ERROR)
                  try { check(readback.contentEquals(record)) { ERROR } }
                  finally { readback.fill(0) }
                }
              })
            }
          }
        }
      }
  }

  private val processLock = Any()
  private const val MAX_RECORD_BYTES = 20 * 1024
  private const val MAX_ENCRYPTED_BYTES = MAX_RECORD_BYTES + 2 + 16 + 16
}
