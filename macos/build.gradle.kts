import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import groovy.json.JsonSlurper

plugins {
  kotlin("jvm") version "2.1.20"
  id("org.jetbrains.compose") version "1.8.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "app.veilark"

val isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation(compose.materialIconsExtended)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
  implementation("org.json:json:20250107")
  implementation("org.yaml:snakeyaml:2.4")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

val privateProperties = Properties().apply {
  val source = file("../private.properties")
  if (source.isFile) source.inputStream().use(::load)
}

fun channelProperty(name: String): String = providers.gradleProperty(name).orNull
  ?: providers.environmentVariable(name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()).orNull
  ?: privateProperties.getProperty(name).orEmpty()

val macosVersion = channelProperty("macosVersion").ifBlank { "1.0.2" }
val macosBuild = channelProperty("macosBuild").toIntOrNull() ?: 10_002
val macosOtaRequireGatekeeper = channelProperty("macosOtaRequireGatekeeper")
  .toBooleanStrictOrNull() ?: true
require(macosVersion.matches(Regex("""\d+\.\d+\.\d+"""))) { "macosVersion must use x.y.z" }
require(macosBuild > 0) { "macosBuild must be positive" }
version = "$macosVersion-dev"

fun kotlinLiteral(value: String): String = buildString {
  append('"')
  value.forEach { character ->
    when (character) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      else -> append(character)
    }
  }
  append('"')
}

fun jsonString(value: String): String = buildString {
  append('"')
  value.forEach { character ->
    when (character) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\b' -> append("\\b")
      '\u000C' -> append("\\f")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
    }
  }
  append('"')
}

val generatedUpdateDir = layout.buildDirectory.dir("generated/update-channel/kotlin")
val generateUpdateChannel by tasks.registering {
  val output = generatedUpdateDir.map {
    it.file("com/example/veilark/update/UpdateChannel.kt")
  }
  outputs.file(output)
  doLast {
    val target = output.get().asFile
    target.parentFile.mkdirs()
    target.writeText(
      """
      package com.example.veilark.update

      internal object UpdateChannel {
        const val CURRENT_VERSION: String = ${kotlinLiteral(macosVersion)}
        const val CURRENT_BUILD: Int = $macosBuild
        const val MANIFEST_URL: String = ${kotlinLiteral(channelProperty("macosOtaManifestUrl"))}
        const val PUBLIC_KEY: String = ${kotlinLiteral(channelProperty("otaPublicKey"))}
        const val REQUIRE_GATEKEEPER: Boolean = $macosOtaRequireGatekeeper
      }
      """.trimIndent() + "\n",
    )
  }
}
kotlin.sourceSets.named("main") {
  kotlin.srcDir(generatedUpdateDir)
}
tasks.named("compileKotlin") {
  dependsOn(generateUpdateChannel)
}

tasks.test {
  useJUnit()
  workingDir = layout.projectDirectory.asFile
}

val createOtaManifest by tasks.registering {
  group = "distribution"
  description = "Create and verify a signed macOS OTA manifest"
  doLast {
    fun required(name: String): String = providers.gradleProperty(name).orNull
      ?.takeIf(String::isNotBlank)
      ?: error("Missing -P$name")

    val dmg = file(required("otaDmg")).canonicalFile
    val manifest = file(required("otaManifestOutput")).canonicalFile
    val signingKeyFile = file(required("otaSigningKey")).canonicalFile
    val releaseVersion = required("otaVersion")
    val releaseBuild = required("otaBuild").toIntOrNull() ?: error("otaBuild must be an integer")
    val releaseArchitecture = required("otaArchitecture").lowercase()
    val releaseUrl = required("otaUrl")
    val releaseNotes = required("otaNotes").trim()
    require(dmg.isFile && dmg.length() in 1..(750L * 1024 * 1024)) { "Invalid OTA DMG" }
    require(signingKeyFile.isFile) { "OTA signing key is missing" }
    require(releaseVersion == macosVersion) { "OTA version must match macosVersion" }
    require(releaseBuild == macosBuild) { "OTA build must match macosBuild" }
    require(releaseArchitecture in setOf("arm64", "amd64", "universal")) { "Unsupported OTA architecture" }
    require(URI(releaseUrl).let { it.scheme == "https" && !it.host.isNullOrBlank() }) { "OTA URL must use HTTPS" }
    require(releaseNotes.isNotBlank() && releaseNotes.length <= 4_000) { "OTA notes must contain 1-4000 characters" }

    val digest = MessageDigest.getInstance("SHA-256")
    dmg.inputStream().buffered().use { input ->
      val buffer = ByteArray(1024 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    val fields = listOf(
      "1",
      "macos",
      releaseVersion,
      releaseBuild.toString(),
      releaseArchitecture,
      releaseUrl,
      sha256,
      releaseNotes,
    )
    val canonical = fields.joinToString("\n") { value ->
      "${value.toByteArray(Charsets.UTF_8).size}:$value"
    }.toByteArray(Charsets.UTF_8)
    val privateDer = signingKeyFile.readText()
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .filterNot(Char::isWhitespace)
      .let(Base64.getDecoder()::decode)
    val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateDer))
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(privateKey)
    signer.update(canonical)
    val signature = signer.sign()

    val publicDer = Base64.getDecoder().decode(channelProperty("otaPublicKey"))
    val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicDer))
    val verifier = Signature.getInstance("Ed25519")
    verifier.initVerify(publicKey)
    verifier.update(canonical)
    check(verifier.verify(signature)) { "OTA signing key does not match the embedded public key" }

    val json = """
      {
        "schemaVersion":1,
        "platform":"macos",
        "version":${jsonString(releaseVersion)},
        "build":$releaseBuild,
        "architecture":${jsonString(releaseArchitecture)},
        "url":${jsonString(releaseUrl)},
        "sha256":${jsonString(sha256)},
        "notes":${jsonString(releaseNotes)},
        "signature":${jsonString(Base64.getEncoder().encodeToString(signature))}
      }
    """.trimIndent() + "\n"
    manifest.parentFile.mkdirs()
    val temporary = File(manifest.parentFile, ".${manifest.name}.${System.nanoTime()}.tmp")
    temporary.writeText(json)
    check(temporary.renameTo(manifest) || run {
      temporary.copyTo(manifest, overwrite = true)
      temporary.delete()
    }) { "Could not publish local OTA manifest" }
    File(manifest.parentFile, "${manifest.name}.sha256").writeText("$sha256  ${dmg.name}\n")
    println("Created signed macOS OTA manifest for $releaseVersion ($releaseBuild), $sha256")
  }
}

val verifyExistingOtaManifest by tasks.registering {
  group = "verification"
  description = "Verify the signed live OTA manifest is older than a candidate build"
  doLast {
    fun required(name: String): String = providers.gradleProperty(name).orNull
      ?.takeIf(String::isNotBlank)
      ?: error("Missing -P$name")

    val manifest = file(required("otaExistingManifest")).canonicalFile
    val candidateBuild = required("otaCandidateBuild").toIntOrNull()
      ?: error("otaCandidateBuild must be an integer")
    val publicDer = Base64.getDecoder().decode(required("otaPublicKey"))
    require(manifest.isFile && manifest.length() in 1..(256 * 1024)) { "Invalid existing OTA manifest" }
    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parse(manifest) as Map<String, Any?>
    fun textField(name: String): String = (json[name] as? String)?.trim()
      ?.takeIf(String::isNotEmpty) ?: error("Existing OTA manifest is missing $name")
    val schema = (json["schemaVersion"] as? Number)?.toInt()
      ?: error("Existing OTA schema is invalid")
    val build = (json["build"] as? Number)?.toInt()
      ?: error("Existing OTA build is invalid")
    val platform = textField("platform")
    val version = textField("version")
    val architecture = textField("architecture").lowercase()
    val url = textField("url")
    val sha256 = textField("sha256").lowercase()
    val notes = (json["notes"] as? String)?.trim().orEmpty()
    val signature = Base64.getDecoder().decode(textField("signature"))
    require(schema == 1 && platform == "macos") { "Existing OTA identity is invalid" }
    require(version.matches(Regex("""\d+\.\d+\.\d+"""))) { "Existing OTA version is invalid" }
    require(architecture in setOf("arm64", "amd64", "universal")) { "Existing OTA architecture is invalid" }
    require(URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() }) { "Existing OTA URL is invalid" }
    require(sha256.matches(Regex("""[0-9a-f]{64}"""))) { "Existing OTA SHA-256 is invalid" }
    require(notes.length <= 4_000) { "Existing OTA notes are invalid" }
    val canonical = listOf(
      schema.toString(), platform, version, build.toString(), architecture, url, sha256, notes,
    ).joinToString("\n") { value -> "${value.toByteArray(Charsets.UTF_8).size}:$value" }
      .toByteArray(Charsets.UTF_8)
    val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicDer))
    val verifier = Signature.getInstance("Ed25519")
    verifier.initVerify(publicKey)
    verifier.update(canonical)
    require(verifier.verify(signature)) { "Existing OTA signature is invalid" }
    require(build < candidateBuild) {
      "Candidate OTA build $candidateBuild is not newer than live build $build"
    }
    println("Verified live OTA build $build before candidate build $candidateBuild")
  }
}

val compileHelper by tasks.registering(Exec::class) {
  val output = layout.buildDirectory.file("helper/veilark-helper")
  inputs.file("helper/veilark-helper.swift")
  outputs.file(output)
  doFirst {
    output.get().asFile.parentFile.mkdirs()
  }
  commandLine(
    "swiftc",
    "-O",
    *if (macosOtaRequireGatekeeper) arrayOf("-D", "VEILARK_REQUIRE_GATEKEEPER") else emptyArray(),
    "-o", output.get().asFile.absolutePath,
    file("helper/veilark-helper.swift").absolutePath,
  )
  onlyIf { isMacOs }
  doLast {
    check(executionResult.get().exitValue == 0 && output.get().asFile.isFile) {
      "veilark-helper compilation failed"
    }
    val bundled = file("packaging/common/veilark-helper")
    bundled.parentFile.mkdirs()
    output.get().asFile.copyTo(bundled, overwrite = true)
    check(bundled.setExecutable(true, false) || bundled.canExecute()) {
      "veilark-helper is not executable"
    }
  }
}

val compileUpdater by tasks.registering(Exec::class) {
  val output = layout.buildDirectory.file("updater/veilark-updater")
  inputs.file("updater/veilark-updater.swift")
  outputs.file(output)
  doFirst {
    output.get().asFile.parentFile.mkdirs()
  }
  commandLine(
    "swiftc",
    "-O",
    "-o", output.get().asFile.absolutePath,
    file("updater/veilark-updater.swift").absolutePath,
  )
  onlyIf { isMacOs }
  doLast {
    check(executionResult.get().exitValue == 0 && output.get().asFile.isFile) {
      "veilark-updater compilation failed"
    }
    val bundled = file("packaging/common/veilark-updater")
    bundled.parentFile.mkdirs()
    output.get().asFile.copyTo(bundled, overwrite = true)
    check(bundled.setExecutable(true, false) || bundled.canExecute()) {
      "veilark-updater is not executable"
    }
  }
}

tasks.named("processResources") {
  dependsOn(compileHelper, compileUpdater)
}

fun File.markBundledEnginesExecutable() {
  listOf("sing-box", "trusttunnel_client", "veilark-helper", "veilark-updater").forEach { name ->
    val binary = resolve(name)
    if (binary.isFile) binary.setExecutable(true, false)
  }
}

val verifyBundledAssets by tasks.registering(Exec::class) {
  dependsOn(compileHelper, compileUpdater)
  onlyIf { isMacOs }
  commandLine("bash", file("scripts/verify-bundled-assets.sh").absolutePath)
}

val verifyPackagedDmg by tasks.registering(Exec::class) {
  onlyIf { isMacOs }
  commandLine("bash", file("scripts/verify-packaged-dmg.sh").absolutePath)
}

tasks.matching { it.name == "packageDmg" }.configureEach {
  dependsOn(verifyBundledAssets)
  finalizedBy(verifyPackagedDmg)
}

tasks.matching {
  it.name == "createDistributable" ||
    it.name == "prepareAppResources" ||
    it.name.startsWith("package")
}.configureEach {
  dependsOn(verifyBundledAssets)
  doLast {
    file("packaging/common").markBundledEnginesExecutable()
    file("build/compose/tmp/prepareAppResources").markBundledEnginesExecutable()
    file("build/compose/binaries/main/app/Veilark.app/Contents/app/resources")
      .markBundledEnginesExecutable()
  }
}

compose.desktop {
  application {
    mainClass = "app.veilark.macos.MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Dmg)
      packageName = "Veilark"
      packageVersion = macosVersion
      description = "Veilark VPN client for macOS"
      copyright = "GPL-3.0-or-later"
      vendor = "Veilark"
      appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging"))
      macOS {
        packageBuildVersion = macosBuild.toString()
        bundleID = "app.veilark.macos"
        dockName = "Veilark"
        infoPlist {
          extraKeysRawXml = """
            <key>LSUIElement</key>
            <false/>
            <key>NSHighResolutionCapable</key>
            <true/>
          """.trimIndent()
        }
      }
    }
  }
}
