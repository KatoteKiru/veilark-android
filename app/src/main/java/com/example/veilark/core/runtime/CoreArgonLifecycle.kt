package com.example.veilark.core.runtime

import com.example.veilark.profile.CoreArgonHandshakeProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Matched Argon ABI only; injected implementations also support lifecycle mock tests. */
internal interface CoreArgonHandleApi {
  fun awaitAuthenticated(handle: Long, output: IntArray): Int
  /** Additive capability; old injected/native implementations reject v2. */
  fun awaitAuthenticatedV2(handle: Long, output: IntArray): Int = -3
  fun attachTun(handle: Long, borrowedTunFd: Int): Int
  fun attachRuDns(handle: Long, borrowedResolverFd: Int): Int
  fun awaitRunning(handle: Long): Int
  fun awaitTerminal(handle: Long): Int
  fun status(handle: Long): Int
  fun stop(handle: Long): Int
  fun join(handle: Long): Int
  /** Optional matched native extension. Old libraries must not be called. */
  fun terminalOutcome(handle: Long): Int? = null
  fun release(handle: Long): Int
}

internal class CoreArgonHandleTicket internal constructor(
  val handle: Long,
  val handshakeProfile: CoreArgonHandshakeProfile = CoreArgonHandshakeProfile.LEGACY,
) {
  /** Published only after actual join, outcome read and successful disposal. */
  @Volatile var releasedTerminalOutcome: Int? = null
    internal set
}
internal class CoreArgonFailure(val operation: String, val code: Long) :
  IllegalStateException("Veilark Core $operation failed ($code)")
internal data class CoreArgonAuthenticated(val ipv4: String, val mtu: Int)

/** Owns duplicated native resources until successful join AND release. No readiness inference. */
internal class CoreArgonLifecycle(private val api: CoreArgonHandleApi) {
  private class Owner(val ticket: CoreArgonHandleTicket) {
    var cleaning = false
    val callbacks = mutableListOf<Job>()
    var waiters = 0
    var drained = CompletableDeferred<Unit>().apply { complete(Unit) }
  }
  private val ownershipMutex = Mutex()
  private var owner: Owner? = null

  /** begin synchronously duplicates borrowed UDP; closeOriginals always runs, even on rejection. */
  suspend fun startBorrowed(begin: () -> Long, closeOriginals: () -> Unit): CoreArgonHandleTicket =
    startBorrowedWithProfile(CoreArgonHandshakeProfile.LEGACY, begin, closeOriginals)

  suspend fun startBorrowedWithProfile(
    profile: CoreArgonHandshakeProfile, begin: () -> Long, closeOriginals: () -> Unit,
  ): CoreArgonHandleTicket {
    val caller = currentCoroutineContext()
    var admitted: CoreArgonHandleTicket? = null
    try {
      val ticket = withContext(NonCancellable + Dispatchers.IO) {
        ownershipMutex.withLock {
          try {
            caller.ensureActive()
            check(owner == null) { "Previous Veilark Core session has not been released" }
            val handle = begin()
            if (handle <= 0) throw CoreArgonFailure("begin", handle)
            // Admission precedes close: even a throwing close cannot lose native ownership.
            CoreArgonHandleTicket(handle, profile).also { admitted = it; owner = Owner(it) }
          } finally { closeOriginals() }
        }
      }
      caller.ensureActive()
      return ticket
    } catch (failure: Throwable) {
      admitted?.let { stopAndRelease(it) }
      throw failure
    }
  }

  suspend fun awaitAuthenticated(ticket: CoreArgonHandleTicket): CoreArgonAuthenticated {
    val v2 = ticket.handshakeProfile != CoreArgonHandshakeProfile.LEGACY
    val output = IntArray(if (v2) 7 else 5)
    try {
      waitNative(ticket) {
        if (v2) api.awaitAuthenticatedV2(ticket.handle, output)
        else api.awaitAuthenticated(ticket.handle, output)
      }
      check(output.take(4).all { it in 0..255 } && output[0] in 1..223 && output[0] != 127 &&
        output[4] in 1280..ticket.handshakeProfile.maximumMtu) { "Invalid authenticated Veilark Core configuration" }
      check(!v2 || output[5] == ticket.handshakeProfile.wireProfile &&
        output[6] == ticket.handshakeProfile.feedbackVersion) { "Veilark Core authenticated profile mismatch" }
      return CoreArgonAuthenticated(output.take(4).joinToString("."), output[4])
    } catch (failure: Throwable) {
      stopAndRelease(ticket)
      throw failure
    }
  }

  /** Only the temporary borrowed descriptor is closed; caller may retain a separate kill-switch TUN. */
  suspend fun attachBorrowed(ticket: CoreArgonHandleTicket, borrowedTunFd: Int, closeOriginals: () -> Unit) {
    installBorrowed(ticket, borrowedTunFd, closeOriginals, "attach", api::attachTun)
  }

  /** Native enforces authenticated RU mode and exactly one pre-TUN installation. */
  suspend fun attachRuDnsBorrowed(ticket: CoreArgonHandleTicket, borrowedResolverFd: Int, closeOriginals: () -> Unit) {
    installBorrowed(ticket, borrowedResolverFd, closeOriginals, "attach_dns", api::attachRuDns)
  }

  private suspend fun installBorrowed(
    ticket: CoreArgonHandleTicket, borrowedFd: Int, closeOriginals: () -> Unit,
    operation: String, install: (Long, Int) -> Int,
  ) {
    val caller = currentCoroutineContext()
    try {
      withContext(NonCancellable + Dispatchers.IO) {
        ownershipMutex.withLock {
          try {
            caller.ensureActive()
            requireOwner(ticket)
            val code = install(ticket.handle, borrowedFd)
            if (code != 0) throw CoreArgonFailure(operation, code.toLong())
          } finally { closeOriginals() }
        }
      }
      caller.ensureActive()
    } catch (failure: Throwable) {
      stopAndRelease(ticket)
      throw failure
    }
  }

  suspend fun awaitRunning(ticket: CoreArgonHandleTicket) {
    try { waitNative(ticket) { api.awaitRunning(ticket.handle) } }
    catch (failure: Throwable) { stopAndRelease(ticket); throw failure }
  }

  /**
   * Register a structured callback worker BEFORE it can enter JNI. Cleanup first
   * wakes native observers, then cancels/drains every worker, and only then joins
   * and releases the handle. Worker blocks must never call stopAndRelease (they
   * may signal native Stop); otherwise they would await their own completion.
   */
  suspend fun startCallbacks(
    ticket: CoreArgonHandleTicket, scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit,
  ): Job {
    val caller = currentCoroutineContext()
    val worker = scope.launch(start = CoroutineStart.LAZY, block = block)
    var registered = false
    try {
      withContext(NonCancellable) {
        ownershipMutex.withLock {
          caller.ensureActive()
          val lease = requireOwner(ticket)
          check(lease.callbacks.size < 2) { "Too many Veilark Core callback workers" }
          lease.callbacks += worker
          registered = true
        }
      }
      // Concurrent cleanup may already have cancelled this lazy worker. It may
      // never start outside owner custody, even if registration is cancelled.
      worker.start()
      caller.ensureActive()
      return worker
    } finally {
      if (!registered) withContext(NonCancellable) { worker.cancel(); worker.join() }
    }
  }

  /** Matched native ABI: latched Stop=4 or worker End=6, without periodic polling. */
  suspend fun awaitTerminal(ticket: CoreArgonHandleTicket): Int =
    waitNative(ticket, accepts = { it == 4 || it == 6 }) { api.awaitTerminal(ticket.handle) }

  private fun requireOwner(ticket: CoreArgonHandleTicket): Owner =
    checkNotNull(owner?.takeIf { it.ticket === ticket && !it.cleaning }) { "Veilark Core session unavailable or replaced" }

  /** Cancellable await sends stop BEFORE structured scope waits for blocking JNI to return. */
  private suspend fun waitNative(
    ticket: CoreArgonHandleTicket, accepts: (Int) -> Boolean = { it == 0 }, wait: () -> Int,
  ): Int {
    val lease = ownershipMutex.withLock {
      currentCoroutineContext().ensureActive()
      requireOwner(ticket).also {
        if (it.waiters++ == 0) it.drained = CompletableDeferred()
      }
    }
    try {
      return coroutineScope {
        val waiter = async(Dispatchers.IO) { wait() }
        try {
          val code = waiter.await()
          if (!accepts(code)) throw CoreArgonFailure("wait", code.toLong())
          ownershipMutex.withLock { currentCoroutineContext().ensureActive(); requireOwner(ticket) }
          code
        } catch (failure: Throwable) {
          withContext(NonCancellable + Dispatchers.IO) {
            ownershipMutex.withLock {
              if (owner === lease) api.stop(ticket.handle)
            }
          }
          throw failure
        } finally {
          // Native wait must never outlive its owner, including cancellation during JNI.
          withContext(NonCancellable) { waiter.join() }
        }
      }
    } finally {
      withContext(NonCancellable) {
        ownershipMutex.withLock { if (--lease.waiters == 0) lease.drained.complete(Unit) }
      }
    }
  }

  suspend fun status(): Int? = withContext(Dispatchers.IO) {
    ownershipMutex.withLock { owner?.let { api.status(it.ticket.handle) } }
  }

  /** Concurrent cleanup returns false. Failed join/release retains ownership for later retry. */
  suspend fun stopAndRelease(expected: CoreArgonHandleTicket? = null): Boolean =
    withContext(NonCancellable + Dispatchers.IO) {
      val admitted = ownershipMutex.withLock {
        val current = owner ?: return@withContext true
        if (expected != null && current.ticket !== expected) return@withContext true
        if (current.cleaning) return@withContext false
        current.cleaning = true
        current
      }
      var released = false
      try {
        val stopCode = api.stop(admitted.ticket.handle)
        admitted.callbacks.forEach { it.cancel() }
        if (stopCode != 0) return@withContext false // Retain custody; never release live callbacks.
        admitted.callbacks.forEach { it.join() }
        admitted.drained.await()
        if (api.join(admitted.ticket.handle) != 0) return@withContext false
        // This is optional provenance, never ownership authority. Missing or
        // failed reads keep recovery conservative but must not prevent disposal.
        val outcome = runCatching { api.terminalOutcome(admitted.ticket.handle) }.getOrNull()
          ?.takeIf { it == 2 || it == 3 || it == 24 || it == 26 ||
            admitted.ticket.handshakeProfile.feedbackVersion == 1 && (it == 28 || it == 29) }
        // Status reads are short but may not race disposal.
        ownershipMutex.withLock {
          if (api.release(admitted.ticket.handle) == 0) {
            admitted.ticket.releasedTerminalOutcome = outcome
            if (owner === admitted) owner = null
            released = true
          }
        }
        released
      } finally {
        ownershipMutex.withLock { if (owner === admitted) admitted.cleaning = false }
      }
    }
}
