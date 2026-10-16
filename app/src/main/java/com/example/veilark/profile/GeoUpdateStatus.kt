package com.example.veilark.profile

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Metadata only: never persists source URLs, credentials or exception messages. */
data class GeoUpdateStatus(
  val lastAttemptAtMillis: Long? = null,
  val lastSuccessAtMillis: Long? = null,
  val result: Result = Result.NEVER,
  val activeGeneration: String? = null,
  val sourceGeneratedAt: String? = null,
) {
  enum class Result { NEVER, UPDATING, SUCCESS, FAILED, CANCELLED, INTERRUPTED }

  fun observed(updatingInThisProcess: Boolean, publishedAtMillis: Long? = null): GeoUpdateStatus {
    val committed = publishedAtMillis != null &&
      (lastAttemptAtMillis == null || publishedAtMillis >= lastAttemptAtMillis) && !updatingInThisProcess
    return copy(
      result = when {
        committed -> Result.SUCCESS
        result == Result.UPDATING && !updatingInThisProcess -> Result.INTERRUPTED
        else -> result
      },
      lastSuccessAtMillis = publishedAtMillis ?: lastSuccessAtMillis,
    )
  }

  fun started(now: Long) = copy(lastAttemptAtMillis = now, result = Result.UPDATING)
  fun succeeded(now: Long) = copy(lastSuccessAtMillis = now, result = Result.SUCCESS)
  fun failed(cancelled: Boolean = false) = copy(
    result = if (cancelled) Result.CANCELLED else Result.FAILED,
  )
}

/** Atomic replacement keeps the previous status readable after a failed write. */
internal class GeoUpdateStatusStore(private val file: File) {
  fun read(): GeoUpdateStatus = runCatching {
    check(file.isFile && file.length() in 1..4096)
    val values = Properties().apply { file.inputStream().use(::load) }
    check(values.getProperty("version") == "1")
    fun timestamp(key: String): Long? = values.getProperty(key)?.toLong()?.also { check(it > 0) }
    GeoUpdateStatus(
      lastAttemptAtMillis = timestamp("attempt"),
      lastSuccessAtMillis = timestamp("success"),
      result = GeoUpdateStatus.Result.valueOf(values.getProperty("result")),
    )
  }.getOrElse { GeoUpdateStatus() }

  fun write(status: GeoUpdateStatus) {
    val values = Properties().apply {
      setProperty("version", "1")
      setProperty("result", status.result.name)
      status.lastAttemptAtMillis?.let { setProperty("attempt", it.toString()) }
      status.lastSuccessAtMillis?.let { setProperty("success", it.toString()) }
    }
    val temporary = File(file.parentFile, "${file.name}.tmp")
    try {
      temporary.outputStream().use { output ->
        values.store(output, null)
        output.fd.sync()
      }
      try {
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING)
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      temporary.delete()
    }
  }
}
