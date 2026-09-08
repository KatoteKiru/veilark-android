package com.example.veilark.io

import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Small synchronous boundary; callers use an interruptible IO dispatcher. Never includes arguments in errors. */
object BoundedProcess {
  data class Output(val exitCode: Int, val text: String)

  fun run(
    command: List<String>,
    timeoutMillis: Long = 15_000,
    input: String? = null,
    maxOutputBytes: Int = 64 * 1024,
  ): Output {
    require(timeoutMillis > 0 && maxOutputBytes > 0)
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val reader = FutureTask {
      process.inputStream.use { stream ->
        val bytes = stream.readAtMost(maxOutputBytes + 1)
        if (bytes.size > maxOutputBytes) {
          process.destroyForcibly()
          throw IOException("Process output exceeded safety limit")
        }
        bytes.toString(Charsets.UTF_8)
      }
    }
    val thread = Thread(reader, "veilark-process-output").apply { isDaemon = true; start() }
    try {
      process.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
      check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "Process timed out" }
      return Output(process.exitValue(), reader.get(1, TimeUnit.SECONDS))
    } finally {
      if (process.isAlive) process.destroyForcibly()
      runCatching { process.inputStream.close() }
      runCatching { process.outputStream.close() }
      reader.cancel(true)
      thread.interrupt()
    }
  }
}
