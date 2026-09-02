package com.example.veilark.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoIpRuCatalogTest {
  @Test
  fun extractsIpv4AndIpv6CidrsIncludingNestedRulesWithoutDuplicates() {
    val parsed = GeoIpRuCatalog.parse(
      """
      {
        "version": 3,
        "rules": [
          {"ip_cidr": ["5.136.0.0/13", "2a00:f480::/29"]},
          {"type": "logical", "rules": [{"ip_cidr": ["5.136.0.0/13", "31.13.16.0/20"]}]}
        ]
      }
      """.trimIndent(),
    )

    assertEquals(listOf("5.136.0.0/13", "2a00:f480::/29", "31.13.16.0/20"), parsed)
  }

  @Test
  fun rejectsMalformedOrSingleStackData() {
    assertThrows(IllegalArgumentException::class.java) {
      GeoIpRuCatalog.parse("""{"version":3,"rules":[{"ip_cidr":["5.136.0.0/13"]}]}""")
    }
    assertThrows(IllegalArgumentException::class.java) {
      GeoIpRuCatalog.parse("""{"version":3,"rules":[{"ip_cidr":["example.com/24","2a00:f480::/29"]}]}""")
    }
  }

  @Test
  fun canonicalTokenHashIsStable() {
    assertEquals(
      "979d8d695e14de24692bd265540b8175882ecfd3070eacafd2404a0e2f88bee3",
      GeoIpRuCatalog.tokenSha256(listOf("5.136.0.0/13", "2a00:f480::/29")),
    )
  }
}
