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
    val manifestUri = URI(channelProperty("macosOtaManifestUrl"))
    fun effectivePort(uri: URI): Int = when (uri.port) {
      -1 -> 443
      in 1..65_535 -> uri.port
      else -> error("Invalid OTA port")
    }
    val releaseUri = URI(releaseUrl)
    require(dmg.isFile && dmg.length() in 1..(750L * 1024 * 1024)) { "Invalid OTA DMG" }
    require(signingKeyFile.isFile) { "OTA signing key is missing" }
    require(releaseVersion == macosVersion) { "OTA version must match macosVersion" }
    require(releaseBuild == macosBuild) { "OTA build must match macosBuild" }
    require(releaseArchitecture in setOf("arm64", "amd64", "universal")) { "Unsupported OTA architecture" }
    require(
      manifestUri.scheme.equals("https", ignoreCase = true) &&
        !manifestUri.host.isNullOrBlank() &&
        manifestUri.userInfo == null &&
        releaseUri.scheme.equals("https", ignoreCase = true) &&
        releaseUri.userInfo == null &&
        releaseUri.host.equals(manifestUri.host, ignoreCase = true) &&
        effectivePort(releaseUri) == effectivePort(manifestUri),
    ) { "OTA URL must use the configured HTTPS host and port" }
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
    val artifactSize = dmg.length()
    val fields = listOf(
      "2",
      "macos",
      releaseVersion,
      releaseBuild.toString(),
      releaseArchitecture,
      releaseUrl,
      sha256,
      artifactSize.toString(),
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
        "schemaVersion":2,
        "platform":"macos",
        "version":${jsonString(releaseVersion)},
        "build":$releaseBuild,
        "architecture":${jsonString(releaseArchitecture)},
        "url":${jsonString(releaseUrl)},
        "sha256":${jsonString(sha256)},
        "size":$artifactSize,
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
    println("Created signed macOS OTA manifest for $releaseVersion ($releaseBuild), $artifactSize bytes, $sha256")
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
    val manifestUri = URI(channelProperty("macosOtaManifestUrl"))
    val artifactUri = URI(url)
    fun effectivePort(uri: URI): Int = when (uri.port) {
      -1 -> 443
      in 1..65_535 -> uri.port
      else -> error("Invalid OTA port")
    }
    val sha256 = textField("sha256").lowercase()
    val rawSize = json["size"] as? Number
    val size = if (schema == 2) {
      rawSize?.toLong() ?: error("Existing OTA size is invalid")
    } else {
      null
    }
    val notes = (json["notes"] as? String)?.trim().orEmpty()
    val signature = Base64.getDecoder().decode(textField("signature"))
    require(schema in setOf(1, 2) && platform == "macos") { "Existing OTA identity is invalid" }
    require(version.matches(Regex("""\d+\.\d+\.\d+"""))) { "Existing OTA version is invalid" }
    require(architecture in setOf("arm64", "amd64", "universal")) { "Existing OTA architecture is invalid" }
    require(
      manifestUri.scheme.equals("https", ignoreCase = true) &&
        !manifestUri.host.isNullOrBlank() &&
        manifestUri.userInfo == null &&
        artifactUri.scheme.equals("https", ignoreCase = true) &&
        artifactUri.userInfo == null &&
        artifactUri.host.equals(manifestUri.host, ignoreCase = true) &&
        effectivePort(artifactUri) == effectivePort(manifestUri),
    ) { "Existing OTA URL is invalid" }
    require(sha256.matches(Regex("""[0-9a-f]{64}"""))) { "Existing OTA SHA-256 is invalid" }
    require(
      schema == 1 || (
        rawSize != null && rawSize.toDouble().isFinite() && rawSize.toLong().toDouble() == rawSize.toDouble()
      ),
    ) { "Existing OTA size is invalid" }
    require(size == null || size in 1..(750L * 1024 * 1024)) { "Existing OTA size is invalid" }
    require(notes.length <= 4_000) { "Existing OTA notes are invalid" }
    val fields = mutableListOf(
      schema.toString(), platform, version, build.toString(), architecture, url, sha256,
    )
    if (schema == 2) fields += size!!.toString()
    fields += notes
    val canonical = fields.joinToString("\n") { value -> "${value.toByteArray(Charsets.UTF_8).size}:$value" }
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

val generateAppIcon by tasks.registering(Exec::class) {
  val output = layout.buildDirectory.file("branding/Veilark.icns")
  inputs.files("src/main/java/app/veilark/macos/BrandIcon.java", "src/main/resources/brand/veilark-mark.svg")
  outputs.file(output)
  commandLine(
    File(System.getProperty("java.home"), "bin/java").absolutePath,
    file("src/main/java/app/veilark/macos/BrandIcon.java").absolutePath,
    file("src/main/resources/brand/veilark-mark.svg").absolutePath,
    output.get().asFile.absolutePath,
  )
}

val compileNativeChrome by tasks.registering(Exec::class) {
  val output = layout.buildDirectory.file("native/libveilark-chrome.dylib")
  inputs.file("native/chrome.m")
  outputs.file(output)
  onlyIf { isMacOs }
  doFirst { output.get().asFile.parentFile.mkdirs() }
  commandLine(
    "clang", "-dynamiclib", "-fobjc-arc", "-Wall", "-Wextra", "-Werror",
    "-Wl,-install_name,@rpath/libveilark-chrome.dylib",
    "-Wno-unused-parameter", "-mmacosx-version-min=12.0", "-framework", "Cocoa",
    "-framework", "QuartzCore",
    "-I${System.getProperty("java.home")}/include",
    "-I${System.getProperty("java.home")}/include/darwin",
    file("native/chrome.m").absolutePath, "-o", output.get().asFile.absolutePath,
  )
  doLast {
    check(output.get().asFile.isFile) { "Native chrome compilation failed" }
    val bundled = file("packaging/common/libveilark-chrome.dylib")
    bundled.parentFile.mkdirs()
    output.get().asFile.copyTo(bundled, overwrite = true)
  }
}

val compileHelper by tasks.registering(Exec::class) {
  val output = layout.buildDirectory.file("helper/veilark-helper")
  inputs.file("helper/main.swift")
  inputs.file("helper/HelperConfigPolicy.swift")
  outputs.file(output)
  doFirst {
    output.get().asFile.parentFile.mkdirs()
  }
  commandLine(
    "swiftc",
    "-O",
    *if (macosOtaRequireGatekeeper) arrayOf("-D", "VEILARK_REQUIRE_GATEKEEPER") else emptyArray(),
    "-o", output.get().asFile.absolutePath,
    file("helper/main.swift").absolutePath,
    file("helper/HelperConfigPolicy.swift").absolutePath,
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
    *if (macosOtaRequireGatekeeper) arrayOf("-D", "VEILARK_REQUIRE_GATEKEEPER") else emptyArray(),
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
  dependsOn(compileHelper, compileUpdater, compileNativeChrome, generateAppIcon)
}

fun File.markBundledEnginesExecutable() {
  listOf("sing-box", "trusttunnel_client", "veilark-helper", "veilark-updater").forEach { name ->
    val binary = resolve(name)
    if (binary.isFile) binary.setExecutable(true, false)
  }
}

val verifyBundledAssets by tasks.registering(Exec::class) {
  dependsOn(compileHelper, compileUpdater, compileNativeChrome)
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
    jvmArgs("-Dapple.awt.enableTemplateImages=true")
    nativeDistributions {
      targetFormats(TargetFormat.Dmg)
      packageName = "Veilark"
      packageVersion = macosVersion
      description = "Veilark VPN client for macOS"
      copyright = "GPL-3.0-or-later"
      vendor = "Veilark"
      appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging"))
      macOS {
        iconFile.set(layout.buildDirectory.file("branding/Veilark.icns"))
        packageBuildVersion = macosBuild.toString()
        bundleID = "app.veilark.macos"
        dockName = "Veilark"
        infoPlist {
          // `veilark://import?url=...` hands a subscription to the running app through
          // Launch Services; Main.kt validates it with ImportDeepLink before use.
          extraKeysRawXml = """
            <key>LSUIElement</key>
            <false/>
            <key>NSHighResolutionCapable</key>
            <true/>
            <key>CFBundleURLTypes</key>
            <array>
              <dict>
                <key>CFBundleURLName</key>
                <string>app.veilark.macos.import</string>
                <key>CFBundleURLSchemes</key>
                <array>
                  <string>veilark</string>
                </array>
                <key>CFBundleTypeRole</key>
                <string>Viewer</string>
              </dict>
            </array>
          """.trimIndent()
        }
      }
    }
  }
}
