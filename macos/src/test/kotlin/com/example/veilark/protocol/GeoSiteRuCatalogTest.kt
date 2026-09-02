package com.example.veilark.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoSiteRuCatalogTest {
  @Test
  fun parsesDomainsAndNestedRules() {
    val parsed = GeoSiteRuCatalog.parse(
      """{"version":3,"rules":[{"domain":["WWW.Example.RU."],"domain_suffix":[".service.ru"],"rules":[{"domain":["nested.ru"]}]}]}""",
    )

    assertEquals(listOf("www.example.ru", "service.ru", "nested.ru"), parsed)
  }

  @Test
  fun rejectsWildcardAndKeywordEntries() {
    assertThrows(IllegalArgumentException::class.java) {
      GeoSiteRuCatalog.parse("""{"rules":[{"domain":["*.example.ru"]}]}""")
    }
    // Keyword data is intentionally not converted into an unsafe exclusion.
    assertThrows(IllegalArgumentException::class.java) {
      GeoSiteRuCatalog.parse("""{"rules":[{"domain_keyword":["example"]}]}""")
    }
  }
}
