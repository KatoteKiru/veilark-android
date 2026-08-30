package com.example.veilark.profile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GeoRoutingInstrumentedTest {
  @Test
  fun packagedRuleSetsAreInstalledAndAcceptedByTheAndroidCore() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val ruleSets = GeoRoutingAssets.prepare(context)
    assertTrue(File(ruleSets.geoIpRuPath).isFile)
    assertTrue(File(ruleSets.geoSiteRuPath).isFile)

    val compiled = SubscriptionParser().compile(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
        .toByteArray(),
    )
    val configured = ProfileSelection.applyRouting(
      compiled.json,
      ProfileSelection.ROUTING_RU_DIRECT,
      geoRuleSets = ruleSets,
    )
    Libbox.checkConfig(configured)

    val route = JSONObject(configured).getJSONObject("route")
    assertEquals("direct", route.getJSONArray("rules").getJSONObject(1).getString("outbound"))
  }
}
