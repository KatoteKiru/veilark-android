import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

/** Copy one pinned Core artifact to its own staging tree; never reuse legacy staging. */
abstract class StageReviewedCoreRuntime : DefaultTask() {
  @get:InputFile abstract val sourceRuntime: RegularFileProperty
  @get:OutputDirectory abstract val stagingDirectory: DirectoryProperty
  @get:Input abstract val expectedSha256: Property<String>
  @get:Input abstract val expectedBytes: Property<Long>

  @TaskAction fun stage() {
    val source = sourceRuntime.get().asFile
    fun verified(file: java.io.File): Boolean = file.isFile && file.length() == expectedBytes.get() &&
      MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) } ==
        expectedSha256.get()
    require(verified(source)) { "Core runtime differs from the selected reviewed artifact" }
    val root = stagingDirectory.get().asFile
    val target = root.resolve("arm64-v8a/libveilark_core_runtime.so")
    require(!root.exists() || root.walkTopDown().filter { it.isFile }.all { it == target }) {
      "Unexpected files in reviewed Core runtime staging"
    }
    target.parentFile.mkdirs()
    source.copyTo(target, overwrite = true)
    require(verified(target)) { "Reviewed Core staging verification failed" }
  }
}

abstract class ValidatePrivateOtaRelease : DefaultTask() {
  @get:Input
  abstract val applicationId: Property<String>

  @get:Input
  abstract val manifestUrl: Property<String>

  @get:Input
  abstract val otaHost: Property<String>

  @get:Input
  abstract val otaPort: Property<Int>

  @get:Input
  abstract val publicKey: Property<String>

  @TaskAction
  fun validate() {
    require(applicationId.get() == PRIVATE_OTA_APPLICATION_ID) {
      "Private OTA release applicationId must remain $PRIVATE_OTA_APPLICATION_ID"
    }
    val manifestUri = runCatching { URI(manifestUrl.get()) }
      .getOrElse { throw IllegalArgumentException("Private OTA manifest URL is invalid", it) }
    require(
      manifestUri.scheme.equals("https", ignoreCase = true) &&
        manifestUri.host == otaHost.get() &&
        manifestUri.port == otaPort.get() &&
        manifestUri.userInfo == null &&
        manifestUri.query == null &&
        manifestUri.fragment == null &&
        manifestUri.path == "/veilark/manifest.json"
    ) {
      "Private OTA manifest URL, host and port must describe the pinned HTTPS channel"
    }
    val decodedPublicKey = runCatching { Base64.getDecoder().decode(publicKey.get()) }
      .getOrElse { throw IllegalArgumentException("Private OTA public key is invalid", it) }
    require(decodedPublicKey.size == ED25519_X509_PUBLIC_KEY_SIZE) {
      "Private OTA public key must be an Ed25519 X.509 key"
    }
  }

  companion object {
    private const val PRIVATE_OTA_APPLICATION_ID = "uk.senyasenyavski.veilark"
    private const val ED25519_X509_PUBLIC_KEY_SIZE = 44
  }
}

val veilarkAbis = providers.gradleProperty("veilarkAbis")
  .orNull
  ?.split(",")
  ?.map(String::trim)
  ?.filter(String::isNotEmpty)
  ?.ifEmpty { null }
  ?: listOf("arm64-v8a", "armeabi-v7a")

val veilarkCoreCanaryRequested = providers.gradleProperty("veilarkCoreCanary")
  .map { value ->
    require(value == "true" || value == "false") {
      "veilarkCoreCanary must be exactly 'true' or 'false'"
    }
    value.toBooleanStrict()
  }
  .orElse(false)

val fairwayLabRequested = providers.gradleProperty("veilarkFairwayLab")
  .map { it.toBooleanStrict() }.orElse(false).get()
require(!fairwayLabRequested || !veilarkCoreCanaryRequested.get()) {
  "Fairway lab and legacy Core canary are separate fixtures"
}

// Private candidate only; production/OSS builds do not pick up lab artifacts.
val bundleCoreBetaRuntime = providers.gradleProperty("bundleVeilarkCoreBetaRuntime")
  .map { it.toBooleanStrict() }.orElse(false).get()
require(!fairwayLabRequested || !bundleCoreBetaRuntime) {
  "Fairway lab must not include the old private Core runtime"
}
val fairwayRepairRequested = providers.gradleProperty("veilarkFairwayRepair")
  .map { value ->
    require(value == "true" || value == "false") {
      "veilarkFairwayRepair must be exactly 'true' or 'false'"
    }
    value.toBooleanStrict()
  }.orElse(false).get()
require(!fairwayRepairRequested || bundleCoreBetaRuntime) {
  "Fairway repair requires bundleVeilarkCoreBetaRuntime=true"
}
require(!fairwayRepairRequested || (!fairwayLabRequested && !veilarkCoreCanaryRequested.get())) {
  "Fairway repair cannot be combined with the lab or legacy Core canary"
}
// Named, reviewed inputs only. Keep the accepted window baseline selectable.
val fairwayLabNativeName = providers.gradleProperty("veilarkFairwayLabNative").orElse("window").get()
require(fairwayLabRequested || fairwayLabNativeName == "window") {
  "Alternate Fairway native candidates are lab-only"
}
val fairwayLabNativeSha = when (fairwayLabNativeName) {
  "window" -> "17d375c0b2e3b36457900227c92cee7ceb4b3c9d9ffeb1f0000c1aa9a22bf73e"
  "udp-stall" -> "33bae0e8a8007605f0e4d814c8d09c61724d876014f4d3c11adfdcafae2704d0"
  "tcp-output" -> "c9707f6ed07640bb42b0bf119e993851a110a93334d04f7061a047219d024694"
  "terminal-outcome" -> "3a0f092a43af9d5025e4c5f52789b63fb851691b0a1a2ac7f9633179164cfc8d"
  "active-feedback" -> "08ba3b5dfbe478de42f2319fa840b955249befc728ffc9aefa23dd9993c4df75"
  else -> error("Unknown reviewed Fairway lab native candidate")
}
val fairwayLabNativeBytes = when (fairwayLabNativeName) {
  "window" -> 4_981_248L
  "udp-stall" -> 4_984_032L
  "tcp-output" -> 4_988_384L
  "terminal-outcome" -> 4_993_792L
  "active-feedback" -> 5_016_112L
  else -> error("Unknown reviewed Fairway lab native candidate")
}
val fairwayLabReport = when (fairwayLabNativeName) {
  "window" -> "fairway-window-native-20261020"
  "udp-stall" -> "fairway-udp-stall-native-20261021"
  "tcp-output" -> "fairway-tcp-output-native-20261021"
  "terminal-outcome" -> "core-terminal-outcome-20261021"
  "active-feedback" -> "core-profile2-feedback-20261021"
  else -> error("Unknown reviewed Fairway lab native candidate")
}
val fairwayLabStage = layout.buildDirectory.dir(
  if (fairwayLabNativeName == "window") "fairway-lab-runtime" else "fairway-lab-$fairwayLabNativeName-runtime")
val stageFairwayLabRuntime = if (fairwayLabRequested) {
  tasks.register<StageReviewedCoreRuntime>("stageFairwayLabRuntime") {
    sourceRuntime.set(rootProject.layout.projectDirectory.file(
      "../reports/$fairwayLabReport/$fairwayLabNativeSha/libveilark_core_runtime.so"))
    stagingDirectory.set(fairwayLabStage)
    expectedSha256.set(fairwayLabNativeSha)
    expectedBytes.set(fairwayLabNativeBytes)
    // Recheck the pinned bytes even with configuration/task caches warm.
    outputs.upToDateWhen { false }
  }
} else null
// Explicit local pilot selection; the published beta13 input stays the default.
val fairwayRepairNative = providers.gradleProperty("veilarkFairwayRepairNative")
  .orElse("active-feedback").get()
require(fairwayRepairRequested || fairwayRepairNative == "active-feedback") {
  "Alternate repair artifacts require the private repair variant"
}
val repairArtifact = when (fairwayRepairNative) {
  "active-feedback" -> Triple(
    "core-profile2-feedback-20261021/08ba3b5dfbe478de42f2319fa840b955249befc728ffc9aefa23dd9993c4df75",
    "08ba3b5dfbe478de42f2319fa840b955249befc728ffc9aefa23dd9993c4df75", 5_016_112L)
  "passage-integration" -> Triple("fairway-passage-integration-20261024",
    "af45728304f94abaede6cf88d777ca5109e286fd69442649977c6b0c7b4c72a1", 2_908_232L)
  else -> error("Unknown reviewed Fairway repair native candidate")
}
val fairwayRepairStage = layout.buildDirectory.dir("fairway-repair-runtime-$fairwayRepairNative")
val stageFairwayRepairRuntime = if (fairwayRepairRequested) {
  tasks.register<StageReviewedCoreRuntime>("stageFairwayRepairRuntime") {
    sourceRuntime.set(rootProject.layout.projectDirectory.file(
      "../reports/${repairArtifact.first}/libveilark_core_runtime.so"))
    stagingDirectory.set(fairwayRepairStage)
    expectedSha256.set(repairArtifact.second)
    expectedBytes.set(repairArtifact.third)
    outputs.upToDateWhen { false }
  }
} else null
val coreBetaRuntimeDirectory = if (fairwayRepairRequested) fairwayRepairStage.get().asFile
  else rootProject.file("build/core-stability-runtime")
if (bundleCoreBetaRuntime && !fairwayRepairRequested) {
  val runtime = coreBetaRuntimeDirectory.resolve("arm64-v8a/libveilark_core_runtime.so")
  require(runtime.isFile && runtime.length() == 4_363_168L) { "Verified Core beta ARM64 runtime missing" }
  val digest = MessageDigest.getInstance("SHA-256").digest(runtime.readBytes())
    .joinToString("") { "%02x".format(it) }
  require(digest == "8077c561be347baefb7bb8cd3d54234e0b5880560bc5a2b6a8eab8beee932107") {
    "Core beta runtime hash does not match reviewed artifact"
  }
  require(coreBetaRuntimeDirectory.walkTopDown().filter { it.isFile }.toList() == listOf(runtime)) {
    "Unexpected Core beta artifacts in staging directory"
  }
}

val privatePropertiesFile = rootProject.file("private.properties")
val privateProperties = Properties().apply {
  if (privatePropertiesFile.isFile) {
    privatePropertiesFile.inputStream().use(::load)
  }
}

fun privateProperty(name: String): String? =
  privateProperties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)

fun quotedBuildConfig(value: String): String =
  "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val privateApplicationId = privateProperty("applicationId") ?: "app.veilark.private"
val privateOtaManifestUrl = privateProperty("otaManifestUrl").orEmpty()
val privateOtaHost = privateProperty("otaHost").orEmpty()
val privateOtaPort = privateProperty("otaPort")?.toIntOrNull() ?: -1
val privateOtaPublicKey = privateProperty("otaPublicKey").orEmpty()
val privateSubscriptionHost = privateProperty("subscriptionHost").orEmpty()
val privateSubscriptionPort = privateProperty("subscriptionPort")?.toIntOrNull() ?: -1
val telegramBotUrl = privateProperty("telegramBotUrl")
  ?: "https://t.me/senyavpn_bot?start=client_android"
val webAccountUrl = "https://senyasenyavski.uk/tma/portal/"

val ossKeystorePath = providers.environmentVariable("VEILARK_OSS_KEYSTORE").orNull
val ossKeystorePassword = providers.environmentVariable("VEILARK_OSS_STORE_PASSWORD").orNull
val ossKeyAlias = providers.environmentVariable("VEILARK_OSS_KEY_ALIAS").orNull
val ossKeyPassword = providers.environmentVariable("VEILARK_OSS_KEY_PASSWORD").orNull
val ossSigningConfigured = listOf(
  ossKeystorePath,
  ossKeystorePassword,
  ossKeyAlias,
  ossKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.example.veilark"
    compileSdk = 36
    flavorDimensions += "distribution"

    signingConfigs {
      if (ossSigningConfigured) {
        create("ossRelease") {
          storeFile = rootProject.file(ossKeystorePath!!)
          storePassword = ossKeystorePassword
          keyAlias = ossKeyAlias
          keyPassword = ossKeyPassword
          enableV1Signing = false
          enableV2Signing = true
          enableV3Signing = true
          enableV4Signing = true
        }
      }
    }

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        versionCode = if (fairwayRepairRequested) 83 else 82
        versionName = if (fairwayRepairRequested) "0.9.0-beta13" else "0.9.0-beta12"
        buildConfigField("boolean", "VEILARK_CORE_ENABLED", "false")
        buildConfigField("boolean", "CORE_BETA_RUNTIME_BUNDLED", "false")
        buildConfigField("boolean", "FAIRWAY_LAB_ENABLED", "false")
        buildConfigField("boolean", "FAIRWAY_ROUTING_ENABLED", "false")
        buildConfigField("boolean", "ARGON_TERMINAL_OUTCOME_ENABLED", "false")
        buildConfigField("boolean", "ARGON_ACTIVE_FEEDBACK_ENABLED", "false")
        buildConfigField("String", "TELEGRAM_BOT_URL", quotedBuildConfig(telegramBotUrl))
        buildConfigField("String", "WEB_ACCOUNT_URL", quotedBuildConfig(webAccountUrl))
        ndk {
            abiFilters += veilarkAbis
        }
    }

    buildTypes {
        release {
            signingConfig = null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (fairwayRepairRequested) {
              buildConfigField("boolean", "FAIRWAY_ROUTING_ENABLED", "true")
              buildConfigField("boolean", "ARGON_TERMINAL_OUTCOME_ENABLED", "true")
              buildConfigField("boolean", "ARGON_ACTIVE_FEEDBACK_ENABLED", "true")
            }
        }
        create("veilarkCoreCanary") {
            initWith(getByName("debug"))
            isDebuggable = true
            applicationIdSuffix = ".veilarkcorecanary"
            versionNameSuffix = "-veilark-core-canary"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "VEILARK_CORE_ENABLED", "true")
        }
        create("fairwayLab") {
            initWith(getByName("debug"))
            isDebuggable = true
            applicationIdSuffix = ".fairwaylab"
            versionNameSuffix = "-fairway-lab"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "VEILARK_CORE_ENABLED", "true")
            buildConfigField("boolean", "CORE_BETA_RUNTIME_BUNDLED", "true")
            buildConfigField("boolean", "FAIRWAY_LAB_ENABLED", "true")
            buildConfigField("boolean", "ARGON_TERMINAL_OUTCOME_ENABLED",
                (fairwayLabNativeName in setOf("terminal-outcome", "active-feedback")).toString())
            buildConfigField("boolean", "ARGON_ACTIVE_FEEDBACK_ENABLED",
                (fairwayLabNativeName == "active-feedback").toString())
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
        }
    }

    productFlavors {
      create("private") {
        dimension = "distribution"
        applicationId = privateApplicationId
        buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        buildConfigField("boolean", "CORE_BETA_RUNTIME_BUNDLED", bundleCoreBetaRuntime.toString())
        buildConfigField("String", "OTA_MANIFEST_URL", quotedBuildConfig(privateOtaManifestUrl))
        buildConfigField("String", "OTA_HOST", quotedBuildConfig(privateOtaHost))
        buildConfigField("int", "OTA_PORT", privateOtaPort.toString())
        buildConfigField("String", "OTA_PUBLIC_KEY", quotedBuildConfig(privateOtaPublicKey))
        buildConfigField("String", "SUBSCRIPTION_HOST", quotedBuildConfig(privateSubscriptionHost))
        buildConfigField("int", "SUBSCRIPTION_PORT", privateSubscriptionPort.toString())
      }
      create("oss") {
        dimension = "distribution"
        applicationId = "app.veilark.android"
        versionNameSuffix = "-oss"
        buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
        buildConfigField("String", "OTA_MANIFEST_URL", quotedBuildConfig(""))
        buildConfigField("String", "OTA_HOST", quotedBuildConfig(""))
        buildConfigField("int", "OTA_PORT", "-1")
        buildConfigField("String", "OTA_PUBLIC_KEY", quotedBuildConfig(""))
        buildConfigField("String", "SUBSCRIPTION_HOST", quotedBuildConfig(""))
        buildConfigField("int", "SUBSCRIPTION_PORT", "-1")
        if (ossSigningConfigured) {
          signingConfig = signingConfigs.getByName("ossRelease")
        }
      }
    }
    if (bundleCoreBetaRuntime) {
      sourceSets.getByName("private").jniLibs.directories.add(coreBetaRuntimeDirectory.absolutePath)
    }
    if (fairwayLabRequested) {
      sourceSets.getByName("fairwayLab").jniLibs.directories.add(fairwayLabStage.get().asFile.absolutePath)
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
      // Compress identical native payloads; Android extracts the selected ABI on install.
      jniLibs.useLegacyPackaging = true
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

androidComponents {
  beforeVariants(selector().all()) { variantBuilder ->
    val distribution = variantBuilder.productFlavors
      .firstOrNull { it.first == "distribution" }
      ?.second
    if (distribution == "oss" && variantBuilder.buildType == "veilarkCoreCanary") {
      variantBuilder.enable = false
    }
    if (fairwayRepairRequested && (distribution != "private" || variantBuilder.buildType != "release")) {
      variantBuilder.enable = false
    }
  }
  beforeVariants(selector().withBuildType("veilarkCoreCanary")) { variantBuilder ->
    val enabled = veilarkCoreCanaryRequested.get()
    variantBuilder.enable = enabled
    (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = enabled
  }
  beforeVariants(selector().withBuildType("release")) { variantBuilder ->
    if (veilarkCoreCanaryRequested.get() || fairwayLabRequested) {
      variantBuilder.enable = false
    }
  }
  beforeVariants(selector().withBuildType("fairwayLab")) { variantBuilder ->
    val isPrivate = variantBuilder.productFlavors.any { it.first == "distribution" && it.second == "private" }
    variantBuilder.enable = fairwayLabRequested && isPrivate
  }
}

// Fresh APK containers only: incremental replacement can retain large archive
// holes even when indexed entries are correct. Keep compile/resource caches.
val prepareFreshPrivateReleaseApk = tasks.register<Delete>("prepareFreshPrivateReleaseApk") {
  delete(layout.buildDirectory.file("outputs/apk/private/release/app-private-release-unsigned.apk"))
}
val prepareFreshFairwayLabApk = tasks.register<Delete>("prepareFreshFairwayLabApk") {
  delete(layout.buildDirectory.file("outputs/apk/private/fairwayLab/app-private-fairwayLab.apk"))
}
tasks.configureEach {
  if (name == "prePrivateFairwayLabBuild" && stageFairwayLabRuntime != null) {
    dependsOn(stageFairwayLabRuntime)
  }
  if (name == "prePrivateReleaseBuild" && stageFairwayRepairRuntime != null) {
    dependsOn(stageFairwayRepairRuntime)
  }
  if (name == "packagePrivateRelease") dependsOn(prepareFreshPrivateReleaseApk)
  if (name == "packagePrivateFairwayLab") dependsOn(prepareFreshFairwayLabApk)
}

val validatePrivateOtaRelease by tasks.registering(ValidatePrivateOtaRelease::class) {
  group = "verification"
  description = "Fails closed when the private OTA release identity is incomplete or changed"
  applicationId.set(privateApplicationId)
  manifestUrl.set(privateOtaManifestUrl)
  otaHost.set(privateOtaHost)
  otaPort.set(privateOtaPort)
  publicKey.set(privateOtaPublicKey)
}

tasks.configureEach {
  if (name == "prePrivateReleaseBuild") {
    dependsOn(validatePrivateOtaRelease)
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
  implementation("androidx.camera:camera-view:1.6.1")
  implementation("com.google.zxing:core:3.5.3")
  implementation("org.yaml:snakeyaml:2.6")
  // Ed25519 is not supplied by Android's JCA before API33 (minSdk29).
  implementation("com.google.crypto.tink:tink-android:1.23.0")
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

  // Stable production sing-box core. The canary flavor keeps a distinct
  // application id but deliberately uses the same verified core artifact.
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
