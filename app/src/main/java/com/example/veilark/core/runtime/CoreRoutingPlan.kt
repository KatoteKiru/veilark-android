package com.example.veilark.core.runtime

import com.example.veilark.profile.ProfileSelection
import java.util.Collections

/** One validated snapshot used before network admission and again at TUN setup. */
internal class CoreRoutingPlan private constructor(
  val included: List<CoreIpv4Route>,
  val excluded: List<CoreIpv4Route>,
  val nativeMode: Int,
  val partialRu: Boolean,
  val directRuIpv4: Long,
) {
  companion object {
    fun prepare(
      mode: String, direct: String, vpn: String, dns: List<String>, api: Int,
      readAsset: (String) -> ByteArray,
    ): CoreRoutingPlan {
      if (mode == ProfileSelection.ROUTING_RU_DIRECT) {
        val ru = FairwayRuRoutePlan.prepare(api,
          readAsset(FairwayRuRoutePlan.EXACT_ASSET),
          readAsset(FairwayRuRoutePlan.LEGACY_ASSET), dns)
        return CoreRoutingPlan(ru.included, ru.excluded, 1, ru.partial, ru.directRuIpv4)
      }
      val routes = CoreL3Routes.fromPreferences(mode, direct, vpn, dns)
      return CoreRoutingPlan(Collections.unmodifiableList(ArrayList(routes)), emptyList(),
        if (mode == ProfileSelection.ROUTING_ALL) 0 else 2, false, 0)
    }
  }
}
