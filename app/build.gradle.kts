import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kilon.agentvscreen"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kilon.agentvscreen.lab"
        minSdk = 30
        targetSdk = 35
        versionCode = 13
        versionName = "1.1.6-atvsx.5"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Release signing is read from keystore.properties (not committed). See keystore.properties.example.
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val fixedStorePath = System.getenv("ATVSX_SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
    val requireFixedSigning = System.getenv("ATVSX_REQUIRE_FIXED_SIGNING") == "true"
    if (requireFixedSigning) {
        check(fixedStorePath != null && file(fixedStorePath).isFile) { "ATVSX fixed keystore is required" }
        for (name in listOf("ATVSX_SIGNING_STORE_PASSWORD", "ATVSX_SIGNING_KEY_PASSWORD", "ATVSX_SIGNING_KEY_ALIAS")) {
            check(!System.getenv(name).isNullOrBlank()) { "Required signing configuration is missing: $name" }
        }
    }
    signingConfigs {
        if (fixedStorePath != null) create("atvsxLongTerm") {
            storeFile = file(fixedStorePath)
            storeType = "PKCS12"
            storePassword = System.getenv("ATVSX_SIGNING_STORE_PASSWORD")
            keyPassword = System.getenv("ATVSX_SIGNING_KEY_PASSWORD")
            keyAlias = System.getenv("ATVSX_SIGNING_KEY_ALIAS")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        if (ksProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(ksProps.getProperty("storeFile"))
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias")
            keyPassword = ksProps.getProperty("keyPassword")
            // v1 (JAR) as well as v2/v3: some vendor installers and scanners still read the JAR certificate.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("atvsxLongTerm") ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("atvsxLongTerm") ?: signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "onnx" }
    // The built-in Tailscale node is an executable shipped as lib*.so: it must be unpacked into nativeLibraryDir.
    packaging { jniLibs { useLegacyPackaging = true } }
    lint { checkReleaseBuilds = false; abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.17")
}
