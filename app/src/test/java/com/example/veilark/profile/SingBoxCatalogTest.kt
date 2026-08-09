package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class SingBoxCatalogTest {
  @Test
  fun catalogRoundTripPreservesIndependentSubscriptions() {
    val entries = listOf(
      SingBoxCatalogEntry(
        id = "first",
        name = "vpn-one.example",
        config = """{"outbounds":[]}""",
        nodes = listOf(ConnectionNode("nl", "Netherlands", "VLESS")),
        selectedNodeTag = "nl",
        sourceUrl = "https://vpn-one.example/sub/token",
      ),
      SingBoxCatalogEntry(
        id = "second",
        name = "vpn-two.example",
        config = """{"outbounds":[]}""",
        nodes = listOf(ConnectionNode("de", "Germany", "Trojan")),
        selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
        sourceUrl = "https://vpn-two.example/sub/token",
      ),
    )

    assertEquals(entries, SingBoxCatalog.decode(SingBoxCatalog.encode(entries)))
  }

  @Test
  fun differentSubscriptionUrlsReceiveDifferentStableIds() {
    val first = SingBoxCatalog.create(
      config = """{"outbounds":[{"tag":"one"}]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://provider.example/sub/first",
      suggestedName = "Imported profile",
    )
    val second = SingBoxCatalog.create(
      config = """{"outbounds":[{"tag":"two"}]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://provider.example/sub/second",
      suggestedName = "Imported profile",
    )

    assert(first.id != second.id)
    assertEquals("provider.example", first.name)
    assertEquals("provider.example", second.name)
  }

  @Test
  fun refreshedSubscriptionKeepsItsStableId() {
    val before = SingBoxCatalog.create(
      config = """{"version":1}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://provider.example/sub/stable",
      suggestedName = null,
    )
    val after = SingBoxCatalog.create(
      config = """{"version":2}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://provider.example/sub/stable",
      suggestedName = null,
    )

    assertEquals(before.id, after.id)
  }
}
