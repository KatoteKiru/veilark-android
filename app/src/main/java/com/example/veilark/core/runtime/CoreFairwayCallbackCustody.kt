package com.example.veilark.core.runtime

/** Owns at most two passive registrations, including registration/close races.
 * API actions run outside the monitor. invalidate must be fast, idempotent and
 * nonthrowing; successful register transfers custody, successful unregister ends it.
 */
internal class CoreFairwayCallbackCustody<C : Any>(
  private val invalidate: () -> Unit,
  private val register: (C) -> Unit,
  private val unregister: (C) -> Unit,
) : AutoCloseable {
  private val lock = Any()
  private var started = false
  private var closed = false
  private val registered = LinkedHashSet<C>()
  private val unregistering = HashSet<C>()

  fun start(callbacks: List<C>) {
    val plan = callbacks.toList()
    require(plan.size in 1..2 && plan.toSet().size == plan.size)
    synchronized(lock) {
      if (started || closed) return
      started = true
    }
    try {
      for (callback in plan) {
        if (synchronized(lock) { closed }) return
        register(callback)
        val remove = synchronized(lock) {
          registered.add(callback)
          closed
        }
        if (remove) { close(); return }
      }
    } catch (failure: Throwable) {
      // A partial registration failure invalidates leases before rollback.
      try { close() } catch (cleanup: Throwable) {
        if (cleanup !== failure) failure.addSuppressed(cleanup)
      }
      throw failure
    }
  }

  override fun close() {
    synchronized(lock) { closed = true }
    invalidate()
    val remove = synchronized(lock) {
      registered.filter { it !in unregistering }.also { unregistering.addAll(it) }
    }
    var failure: Throwable? = null
    for (callback in remove) {
      try {
        unregister(callback)
        synchronized(lock) { registered.remove(callback) }
      } catch (error: Throwable) {
        val previous = failure
        if (previous == null) failure = error
        else if (previous !== error) previous.addSuppressed(error)
      } finally {
        // Failed unregister retains custody and becomes retryable by later close.
        synchronized(lock) { unregistering.remove(callback) }
      }
    }
    failure?.let { throw it }
  }
}
