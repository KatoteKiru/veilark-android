package com.example.veilark.core.runtime

/** A bound Android service can host several leases, but never overlapping resources. */
internal class CoreBetaSessionGate<T : Any> {
  private var active: T? = null
  private var nativeReleased = false
  private var tunClosed = false
  private var jobTerminal = false
  private var serviceEffectsDone = false

  @Synchronized fun current(): T? = active
  @Synchronized fun admit(session: T): Boolean {
    if (active != null) return false
    active = session
    nativeReleased = false; tunClosed = false; jobTerminal = false; serviceEffectsDone = false
    return true
  }
  @Synchronized fun resources(session: T, native: Boolean, tun: Boolean) {
    if (active !== session) return
    nativeReleased = nativeReleased || native
    tunClosed = tunClosed || tun
  }
  @Synchronized fun terminal(session: T) { if (active === session) jobTerminal = true }
  /** Global service side effects happen before removing the admission fence. */
  @Synchronized fun finishServiceEffects(session: T, effects: () -> Unit) {
    if (active !== session || serviceEffectsDone) return
    effects()
    serviceEffectsDone = true
  }
  @Synchronized fun takeReleased(session: T): Boolean {
    if (active !== session || !nativeReleased || !tunClosed || !jobTerminal || !serviceEffectsDone) return false
    active = null
    return true
  }
}
