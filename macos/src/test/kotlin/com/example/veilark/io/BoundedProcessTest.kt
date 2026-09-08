package com.example.veilark.io

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedProcessTest {
  @get:Rule val folder = TemporaryFolder()
  private fun command(vararg args: String) = listOf(
    File(System.getProperty("java.home"), "bin/java").absolutePath,
    "-cp", File(ProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).absolutePath,
    ProcessFixture::class.java.name,
  ) + args

  @Test fun capturesBoundedOutput() {
    assertEquals("ok", BoundedProcess.run(command("echo")).text)
  }
  @Test fun killsProcessOnTimeout() {
    val pid = File(folder.root, "pid")
    assertThrows(IllegalStateException::class.java) {
      BoundedProcess.run(command("sleep", pid.absolutePath), timeoutMillis = 2_000)
    }
    assertTrue(pid.isFile)
    val process = ProcessHandle.of(pid.readText().toLong())
    if (process.isPresent) process.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
    assertFalse(process.isPresent && process.get().isAlive)
  }
  @Test fun rejectsUnboundedOutput() {
    assertThrows(Exception::class.java) {
      BoundedProcess.run(command("flood"), maxOutputBytes = 1024)
    }
  }
  @Test fun interruptionKillsTheProcess() {
    val pid = File(folder.root, "interrupt-pid")
    val result = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val worker = Thread {
      try { BoundedProcess.run(command("sleep", pid.absolutePath)) }
      catch (failure: Throwable) { result.set(failure) }
    }
    worker.start()
    val deadline = System.nanoTime() + 5_000_000_000
    while (!pid.isFile && System.nanoTime() < deadline) Thread.sleep(10)
    try {
      assertTrue(pid.isFile)
    } finally {
      worker.interrupt()
      worker.join(5_000)
    }
    assertFalse(worker.isAlive)
    assertTrue(result.get() is InterruptedException)
    val process = ProcessHandle.of(pid.readText().toLong())
    if (process.isPresent) process.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
    assertFalse(process.isPresent && process.get().isAlive)
  }
}
