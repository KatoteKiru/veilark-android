package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

  @Test
  fun removingOneSourcePreservesRemoteAndManualPeers() {
    val first = SingBoxCatalog.create(
      config = """{"outbounds":[]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://one.example/sub/a",
      suggestedName = null,
    )
    val second = SingBoxCatalog.create(
      config = """{"outbounds":[]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://two.example/sub/b",
      suggestedName = null,
    )
    val manual = SingBoxCatalog.create(
      config = """{"outbounds":[{"tag":"manual"}]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = null,
      suggestedName = "Manual",
    )

    assertEquals(listOf(second, manual), SingBoxCatalog.removeSource(listOf(first, second, manual), first.id))
  }

  @Test
  fun v1CatalogMigratesWithoutLosingProfileOrActiveNode() {
    val legacy = """
      {
        "version": 1,
        "profiles": [{
          "id": "legacy-id",
          "name": "Legacy",
          "config": "{\"outbounds\":[{\"tag\":\"nl\",\"server\":\"example.org\"}]}",
          "nodes": "[{\"tag\":\"nl\",\"name\":\"NL\",\"protocol\":\"VLESS\"}]",
          "selectedNodeTag": "nl",
          "sourceUrl": null
        }]
      }
    """.trimIndent()

    val migrated = SingBoxCatalog.decode(legacy).single()

    assertEquals("legacy-id", migrated.id)
    assertEquals("nl", migrated.selectedNodeTag)
    assertEquals(SubscriptionOrigin.LEGACY, migrated.origin)
    assertTrue(migrated.nodeFingerprints.containsKey("nl"))
  }

  @Test
  fun refreshingMigratedUrlDoesNotCreateDuplicateSource() {
    val legacy = SingBoxCatalogEntry(
      id = "old-v1-id",
      name = "Provider",
      config = """{"outbounds":[]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://provider.example/sub/token",
      origin = SubscriptionOrigin.LEGACY,
    )
    val refreshed = SingBoxCatalog.create(
      config = """{"outbounds":[{"tag":"new"}]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "HTTPS://PROVIDER.EXAMPLE/sub/token#ignored",
      suggestedName = null,
    )

    assertEquals(listOf(refreshed), SingBoxCatalog.replaceSource(listOf(legacy), refreshed))
  }

  @Test
  fun refreshingOneRemoteSubscriptionPreservesOtherRemoteSubscriptions() {
    val first = SingBoxCatalog.create(
      config = """{"outbounds":[{"tag":"first"}]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://first.example/sub/token",
      suggestedName = null,
    )
    val second = SingBoxCatalog.create(
      config = """{"outbounds":[{"tag":"second"}]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://second.example/sub/token",
      suggestedName = null,
    )
    val refreshedFirst = SingBoxCatalog.create(
      config = """{"outbounds":[{"tag":"first-refreshed"}]}""",
      nodes = emptyList(),
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://first.example/sub/token",
      suggestedName = null,
    )

    val result = SingBoxCatalog.replaceSource(listOf(first, second), refreshedFirst)

    assertEquals(setOf(first.id, second.id), result.mapTo(linkedSetOf()) { it.id })
    assertEquals("first-refreshed", result.first { it.id == first.id }.config.let {
      org.json.JSONObject(it).getJSONArray("outbounds").getJSONObject(0).getString("tag")
    })
  }
}
