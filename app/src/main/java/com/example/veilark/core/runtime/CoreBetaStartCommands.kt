package com.example.veilark.core.runtime

/** Fences slow admission against a later Stop without holding a UI-visible lock during work. */
internal class CoreBetaStartCommands {
  private var generation = 0L
  private var paused = false
  @Synchronized fun begin(): Long {
    check(!paused) { "Core profile is being changed." }
    return ++generation
  }
  @Synchronized fun ifCurrent(expected: Long, action: () -> Unit): Boolean {
    if (paused || expected != generation) return false
    action()
    return true
  }
  @Synchronized fun stop(action: () -> Unit) {
    generation++
    action()
  }
  @Synchronized fun pauseWhen(checkIdle: () -> Boolean) {
    check(!paused && checkIdle()) { "Stop Veilark Core before changing its profile." }
    paused = true
    generation++
  }
  @Synchronized fun resume() { paused = false }
}
