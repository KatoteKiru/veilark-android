package com.example.veilark.ui.main

/**
 * The choice between the two stored Veilark Core routes: Fairway, the direct
 * route, and Passage, the route through legitimate-service relays that is used
 * under whitelists. Offered only when both profiles exist.
 *
 * @property relaySelected Passage is the active route.
 * @property relayAvailable this build's core can connect over Passage (begin_v3).
 * @property coreStopped the route can be changed; Core must not be running.
 */
data class CoreRouteChoice(
  val relaySelected: Boolean,
  val relayAvailable: Boolean,
  val coreStopped: Boolean,
)
