package com.example.veilark

/** One process-local initialization, including failure and recursive-entry fencing. */
internal class NativeInitializationGate(private val initialize: () -> Unit) {
  private enum class State { NEW, INITIALIZING, READY, FAILED }
  private var state = State.NEW
  private var failure: Throwable? = null

  @Synchronized fun requireReady() {
    when (state) {
      State.READY -> return
      State.FAILED -> throw IllegalStateException("Native initialization previously failed", failure)
      State.INITIALIZING -> error("Recursive native initialization")
      State.NEW -> Unit
    }
    state = State.INITIALIZING
    try {
      initialize()
      state = State.READY
    } catch (error: Throwable) {
      failure = error
      state = State.FAILED
      throw error
    }
  }
}
