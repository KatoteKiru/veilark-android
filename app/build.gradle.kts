import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
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
val telegramBotUrl = privateProperty("telegramBotUrl")
  ?: "https://t.me/senyavpn_bot?start=client_android"

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
        versionCode = 48
        versionName = "0.8.0-rc21"
        buildConfigField("boolean", "VEILARK_CORE_ENABLED", "false")
        buildConfigField("String", "TELEGRAM_BOT_URL", quotedBuildConfig(telegramBotUrl))
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
        }
        create("veilarkCoreCanary") {
            initWith(getByName("debug"))
            isDebuggable = true
            applicationIdSuffix = ".veilarkcorecanary"
            versionNameSuffix = "-veilark-core-canary"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "VEILARK_CORE_ENABLED", "true")
        }
    }

    productFlavors {
      create("private") {
        dimension = "distribution"
        applicationId = privateApplicationId
        buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        buildConfigField("String", "OTA_MANIFEST_URL", quotedBuildConfig(privateOtaManifestUrl))
        buildConfigField("String", "OTA_HOST", quotedBuildConfig(privateOtaHost))
        buildConfigField("int", "OTA_PORT", privateOtaPort.toString())
        buildConfigField("String", "OTA_PUBLIC_KEY", quotedBuildConfig(privateOtaPublicKey))
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
        if (ossSigningConfigured) {
          signingConfig = signingConfigs.getByName("ossRelease")
        }
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
}

androidComponents {
  beforeVariants(selector().all()) { variantBuilder ->
    val distribution = variantBuilder.productFlavors
      .firstOrNull { it.first == "distribution" }
      ?.second
    if (distribution == "oss" && variantBuilder.buildType == "veilarkCoreCanary") {
      variantBuilder.enable = false
    }
  }
  beforeVariants(selector().withBuildType("veilarkCoreCanary")) { variantBuilder ->
    val enabled = veilarkCoreCanaryRequested.get()
    variantBuilder.enable = enabled
    (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = enabled
  }
  beforeVariants(selector().withBuildType("release")) { variantBuilder ->
    if (veilarkCoreCanaryRequested.get()) {
      variantBuilder.enable = false
    }
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
  implementation("com.google.mlkit:barcode-scanning:17.3.0")
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
