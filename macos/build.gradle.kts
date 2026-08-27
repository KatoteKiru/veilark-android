import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
  kotlin("jvm") version "2.1.20"
  id("org.jetbrains.compose") version "1.8.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "app.veilark"
version = "1.0.1-dev"

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
        const val MANIFEST_URL: String = ${kotlinLiteral(channelProperty("macosOtaManifestUrl"))}
        const val PUBLIC_KEY: String = ${kotlinLiteral(channelProperty("otaPublicKey"))}
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

tasks.named("processResources") {
  dependsOn(compileHelper)
}

fun File.markBundledEnginesExecutable() {
  listOf("sing-box", "trusttunnel_client", "veilark-helper").forEach { name ->
    val binary = resolve(name)
    if (binary.isFile) binary.setExecutable(true, false)
  }
}

val verifyBundledAssets by tasks.registering(Exec::class) {
  dependsOn(compileHelper)
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
      packageVersion = "1.0.1"
      description = "Veilark VPN client for macOS"
      copyright = "GPL-3.0-or-later"
      vendor = "Veilark"
      appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging"))
      macOS {
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
