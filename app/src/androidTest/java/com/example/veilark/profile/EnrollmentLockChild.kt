package com.example.veilark.profile

import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/** Instrumentation-only app_process lock holder; no Android service/manifest entry. */
object EnrollmentLockChild {
  @JvmStatic fun main(args: Array<String>) {
    check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
    check(args.size == 1)
    val file = File(args[0]).canonicalFile
    check(file.name == "enrollment-v2-store.lock" && file.path.contains("/enrollment-v2-platform-tests/"))
    check(file.parentFile!!.isDirectory)
    RandomAccessFile(file, "rw").use { handle ->
      handle.channel.use { channel ->
        channel.lock().use {
          println("LOCKED")
          System.out.flush()
          System.`in`.read()
        }
      }
    }
  }
}
