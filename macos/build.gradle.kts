import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  kotlin("jvm") version "2.1.20"
  id("org.jetbrains.compose") version "1.8.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "app.veilark"
version = "1.0.0"

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
  isIgnoreExitValue = true
  doLast {
    if (executionResult.get().exitValue != 0) {
      logger.warn("veilark-helper was not compiled (swiftc missing or failed). TUN connect will be unavailable until it is built.")
    } else {
      val bundled = file("packaging/common/veilark-helper")
      bundled.parentFile.mkdirs()
      output.get().asFile.copyTo(bundled, overwrite = true)
      bundled.setExecutable(true, false)
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

tasks.matching {
  it.name == "createDistributable" ||
    it.name == "prepareAppResources" ||
    it.name.startsWith("package")
}.configureEach {
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
      packageVersion = "1.0.0"
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
