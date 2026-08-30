package com.example.veilark.protocol

import com.example.veilark.profile.SubscriptionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustTunnelCatalogTest {
  @Test
  fun catalogRoundTripPreservesMultipleEncryptedPayloadCandidates() {
    val expected = listOf(
      TrustTunnelCatalogEntry("de", "DE Frankfurt TrustTunnel", "[endpoint]\nname=\"DE\""),
      TrustTunnelCatalogEntry("nl", "NL Fast TrustTunnel", "[endpoint]\nname=\"NL\""),
    )

    assertEquals(expected, TrustTunnelCatalog.decode(TrustTunnelCatalog.encode(expected)))
  }

  @Test
  fun sourceRefreshReplacesOnlyNodesOwnedByThatSource() {
    val sourceUrl = "https://provider.example/trust/sub"
    val firstVersion = TrustTunnelCatalog.replaceSourceEntries(
      entries = emptyList(),
      profiles = listOf(
        CompiledTrustTunnelProfile("DE", "[endpoint]\nname=\"DE\"\nhost=\"de.example\""),
        CompiledTrustTunnelProfile("NL", "[endpoint]\nname=\"NL\"\nhost=\"nl.example\""),
      ),
      sourceUrl = sourceUrl,
      origin = SubscriptionOrigin.REMOTE,
      localIdentity = sourceUrl,
    )
    val manual = TrustTunnelCatalog.replaceSourceEntries(
      entries = firstVersion,
      profiles = listOf(
        CompiledTrustTunnelProfile("Manual", "[endpoint]\nname=\"Manual\"\nhost=\"manual.example\""),
      ),
      sourceUrl = null,
      origin = SubscriptionOrigin.MANUAL,
      localIdentity = "manual-source",
    )

    val refreshed = TrustTunnelCatalog.replaceSourceEntries(
      entries = manual,
      profiles = listOf(
        CompiledTrustTunnelProfile("NL2", "[endpoint]\nname=\"NL2\"\nhost=\"nl2.example\""),
      ),
      sourceUrl = sourceUrl,
      origin = SubscriptionOrigin.REMOTE,
      localIdentity = sourceUrl,
    )

    assertEquals(2, refreshed.size)
    assertTrue(refreshed.any { it.name == "Manual" })
    assertTrue(refreshed.any { it.name == "NL2" })
    assertTrue(refreshed.none { it.name == "DE" || it.name == "NL" })
  }

  @Test
  fun deletingTrustSourcePreservesOtherSources() {
    val sourceA = TrustTunnelCatalog.replaceSourceEntries(
      emptyList(),
      listOf(CompiledTrustTunnelProfile("A", "[endpoint]\nname=\"A\"")),
      "https://a.example/sub",
      SubscriptionOrigin.REMOTE,
      "a",
    )
    val both = TrustTunnelCatalog.replaceSourceEntries(
      sourceA,
      listOf(CompiledTrustTunnelProfile("B", "[endpoint]\nname=\"B\"")),
      "https://b.example/sub",
      SubscriptionOrigin.REMOTE,
      "b",
    )
    val aSourceId = sourceA.single().sourceId

    val remaining = TrustTunnelCatalog.removeSource(both, aSourceId)

    assertEquals(listOf("B"), remaining.map { it.name })
  }

  @Test
  fun v1MigrationKeepsLegacyProfileId() {
    val legacy = """
      {"version":1,"profiles":[{
        "id":"active-old-id",
        "name":"Legacy Trust",
        "config":"[endpoint]\\nname=\\\"Legacy Trust\\\""
      }]}
    """.trimIndent()

    val migrated = TrustTunnelCatalog.decode(legacy).single()

    assertEquals("active-old-id", migrated.id)
    assertEquals(SubscriptionOrigin.LEGACY, migrated.origin)
    assertTrue(migrated.sourceId.isNotBlank())
  }
}
