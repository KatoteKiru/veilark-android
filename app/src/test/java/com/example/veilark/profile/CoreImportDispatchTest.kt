package com.example.veilark.profile

import org.junit.Assert.*
import org.junit.Test

class CoreImportDispatchTest {
  @Test fun dispatchesEverySupportedExactSchemaIncludingEscapes() {
    for ((schema, kind) in listOf(
      "veilark-core-beta-profile-v1" to CoreProfileKind.LEGACY_V1,
      "veilark-argon-profile-v1" to CoreProfileKind.ARGON_V1,
      "veilark-argon-profile-v2" to CoreProfileKind.ARGON_V1,
    )) {
      assertEquals(kind, classify("""{"schema":"$schema"}"""))
      assertEquals(kind, classify("""{"\u0073chema":"${schema.replace("v", "\\u0076")}"}"""))
    }
  }

  @Test fun ignoresEmbeddedMarkersAndOtherTopLevelSchemas() {
    for (json in listOf(
      """{"name":"veilark-argon-profile-v2","nested":{"schema":"veilark-core-beta-profile-v1"}}""",
      """{"schema":"other-engine","name":"veilark-argon-profile-v1"}""",
      """{"schema":"prefix-veilark-argon-profile-v2"}""",
      """{"schema":true,"nested":{"schema":"veilark-argon-profile-v2"}}""",
      "{}",
    )) assertNull(classify(json))
  }

  @Test fun keepsUrlsBase64AndOtherNonObjectFormatsOnTheirExistingPath() {
    for (text in listOf("https://example.invalid/profile", "dmVpbGFyay1hcmdvbi1wcm9maWxlLXYy",
      "ss://example", "[1,2]", "proxies:\n - name: example", " \r\n\t")) assertNull(classify(text))
  }

  @Test fun rejectsReservedUnknownSchemasAndDuplicateDecodedSchemaWithoutDowngrade() {
    for (text in listOf(
      """{"schema":"veilark-argon-profile-v999"}""",
      """{"schema":"veilark-core-new"}""",
      """{"schema":"other","schema":"veilark-argon-profile-v2"}""",
      """{"schema":"veilark-argon-profile-v2","\u0073chema":"other"}""",
      """{"schema":"veilark-argon-profile-v2","nested":{"key":1,"key":2}}""",
    )) rejected(text.toByteArray())
  }

  @Test fun malformedCoreObjectsNeverBecomeGenericImports() {
    for (text in listOf(
      """{"schema":"veilark-argon-profile-v2",}""",
      """{"schema":"veilark-argon-profile-v2","x":[1,]}""",
      """{"schema":"veilark-argon-profile-v2","x":{"a":true,}}""",
      """{"schema":"veilark-argon-profile-v2"}trailing""",
      """{"schema":"veilark-argon-profile-v2","x":"\ud800"}""",
    )) rejected(text.toByteArray())
    rejected("""{"schema":"veilark-argon-profile-v2","x":"""".toByteArray() +
      byteArrayOf(0xc0.toByte(), 0xaf.toByte()) + "\"}".toByteArray())
  }

  @Test fun encodingPrefixesCannotHideReservedCoreSchemas() {
    val core = """{"schema":"veilark-argon-profile-v2"}"""
    rejected(byteArrayOf(0xff.toByte()) + core.toByteArray())
    rejected(("\uFEFF" + core).toByteArray())
    assertNull(classify("\uFEFF" + """{"schema":"other","name":"veilark-argon-profile-v2"}"""))
  }

  @Test fun retainsExactCoreByteAndDepthBounds() {
    val valid = """{"schema":"veilark-argon-profile-v2"}"""
    val exact = valid + " ".repeat(16384 - valid.toByteArray().size)
    assertEquals(CoreProfileKind.ARGON_V1, classify(exact))
    rejected((exact + " ").toByteArray())
    rejected("""{"schema":"veilark-argon-profile-v2","x":"${"я".repeat(9000)}"}""".toByteArray())
    rejected(("""{"schema":"veilark-argon-profile-v2","x":""" +
      "[".repeat(20) + "0" + "]".repeat(20) + "}").toByteArray())
  }

  @Test fun genericDocumentsAreNotLimitedByCoreSizeOrRecursiveDepth() {
    val large = """{"data":"${"x".repeat(4 * 1024 * 1024 - 11)}"}"""
    assertEquals(4 * 1024 * 1024, large.toByteArray().size)
    assertNull(classify(large))
    val deep = "[".repeat(10000) + "0" + "]".repeat(10000)
    assertNull(classify("""{"nested":$deep,"schema":"other","flag":false,"n":null,"f":-2.5e+3}"""))
    assertEquals(CoreProfileKind.ARGON_V1,
      classify("""{"nested":[{"n":[1,true,null]}],"schema":"veilark-argon-profile-v2"}"""))
  }

  private fun classify(text: String) = CoreProfileKind.classifyImport(text.toByteArray(Charsets.UTF_8))
  private fun rejected(payload: ByteArray) {
    try {
      CoreProfileKind.classifyImport(payload)
      fail("Invalid reserved profile must not fall through")
    } catch (error: IllegalArgumentException) {
      assertEquals("Invalid Veilark Core profile.", error.message)
      assertNull(error.cause)
    }
  }
}
