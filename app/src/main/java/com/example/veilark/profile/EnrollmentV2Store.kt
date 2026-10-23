package com.example.veilark.profile

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Unactivated production trust boundary. No caller-supplied key map or verified flag.
 * Only a separately authenticated app release may populate this registry; NOT remote data.
 * No UI, fetcher, engine selection or automatic raw-profile migration calls this facade.
 */
internal object EnrollmentV2Store {
  private const val ERROR = "Protected enrollment is unavailable."
  private val releaseIssuerKeys: Map<Pair<String, String>, ByteArray> = emptyMap()

  suspend fun import(context: Context, link: String, ciphertext: ByteArray): EnrollmentV2RecordStore.ImportResult =
    withContext(Dispatchers.IO) {
      check(releaseIssuerKeys.isNotEmpty()) { ERROR }
      EnrollmentV2DraftConsumer.verify(link, ciphertext, releaseIssuerKeys, epochSeconds()).use {
        store(context).import(it, ::epochSeconds)
      }
    }

  suspend fun load(context: Context, issuer: String, profileId: String): EnrollmentV2RecordStore.StoredRecord? =
    withContext(Dispatchers.IO) {
      check(releaseIssuerKeys.isNotEmpty()) { ERROR }
      val value = store(context).load(issuer, profileId) ?: return@withContext null
      if (!releaseIssuerKeys.containsKey(value.issuer to value.keyId)) {
        value.close()
        error(ERROR)
      }
      value
    }

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
