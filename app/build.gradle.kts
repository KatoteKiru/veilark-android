import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

abstract class GenerateEmbeddedTrustProfiles : DefaultTask() {
  @get:InputFiles
  abstract val sourceFiles: ConfigurableFileCollection

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val clientLink = Regex(""""client_link"\s*:\s*"([^"\\]+)"""")
    val links = sourceFiles.files.sortedBy { it.name }.map { source ->
      require(source.isFile) { "Missing embedded TrustTunnel source: $source" }
      clientLink.find(source.readText(Charsets.UTF_8))
        ?.groupValues
        ?.get(1)
        ?.takeIf { it.startsWith("tt://") }
        ?: error("Invalid embedded TrustTunnel source: $source")
    }
    val output = outputDirectory.file("builtin_trust_profiles.txt").get().asFile
    output.parentFile.mkdirs()
    output.writeText(links.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
  }
}

val veilarkAbis = providers.gradleProperty("veilarkAbis")
  .orNull
  ?.split(",")
  ?.map(String::trim)
  ?.filter(String::isNotEmpty)
  ?.ifEmpty { null }
  ?: listOf("arm64-v8a", "armeabi-v7a")

val embeddedTrustSources = listOf(
  rootProject.layout.projectDirectory.file("../secrets/generated/new-nl-trusttunnel.json"),
  rootProject.layout.projectDirectory.file("../secrets/generated/frankfurt-trusttunnel.json"),
)
val generatedTrustAssets = layout.buildDirectory.dir("generated/veilark/trust-assets").get().asFile
val generateEmbeddedTrustProfiles = tasks.register<GenerateEmbeddedTrustProfiles>(
  "generateEmbeddedTrustProfiles",
) {
  sourceFiles.from(embeddedTrustSources)
  outputDirectory.set(generatedTrustAssets)
}

android {
    namespace = "com.example.veilark"
    compileSdk = 36
    defaultConfig {
        applicationId = "uk.senyasenyavski.veilark"
        minSdk = 29
        targetSdk = 36
        versionCode = 34
        versionName = "0.8.0-rc7"
        ndk {
            abiFilters += veilarkAbis
        }
    }

    buildTypes {
        release {
            // Keep OTA compatibility with all Veilark development builds already installed.
            // Migrating to a production key later requires one explicit reinstall.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
    sourceSets.getByName("main").assets.srcDir(generatedTrustAssets)
}

tasks.configureEach {
  if (
    (name.startsWith("merge") && name.endsWith("Assets")) ||
    name.contains("Lint", ignoreCase = true)
  ) {
    dependsOn(generateEmbeddedTrustProfiles)
  }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.camera:camera-camera2:1.6.1")
  implementation("androidx.camera:camera-core:1.6.1")
  implementation("androidx.camera:camera-lifecycle:1.6.1")
  implementation("androidx.camera:camera-mlkit-vision:1.6.1")
  implementation("androidx.camera:camera-view:1.6.1")
  implementation("com.google.mlkit:barcode-scanning:17.3.0")
  implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
  implementation("org.yaml:snakeyaml:2.6")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.json:json:20250517")

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.kotlinx.coroutines.android)

  // Reproducible arm64 build of sing-box 1.13.14 (GPLv3).
  implementation(files("libs/libbox.aar"))

  // Official TrustTunnel Android client built from the upstream source.
  implementation(files("libs/trusttunnel-client.aar"))
  implementation("org.slf4j:slf4j-api:1.7.25")
  implementation("com.github.tony19:logback-android:2.0.0")
  implementation("io.reactivex.rxjava3:rxandroid:3.0.0")
  implementation("com.akuleshov7:ktoml-core:0.7.0")
  implementation("androidx.appcompat:appcompat:1.7.1")
  implementation("com.google.android.material:material:1.12.0")
}
