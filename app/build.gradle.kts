import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Developer-owned Android SDK key. Never commit it or pass it on the command line.
val localMapProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val localMapKey = localMapProperties.getProperty("amap.android.key", "").trim()
require(localMapKey.isEmpty() || localMapKey.matches(Regex("[a-fA-F0-9]{32}"))) {
    "Invalid local Android map key format"
}

val localRepositoryUrl = localMapProperties.getProperty("project.repository.url", "").trim().removeSuffix("/").removeSuffix(".git")
require(localRepositoryUrl.isEmpty() || localRepositoryUrl.matches(Regex("https://github\\.com/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))) {
    "project.repository.url must be a public HTTPS GitHub repository URL"
}

android {
    namespace = "io.github.ibudaoa"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ibudaoa"
        minSdk = 29
        targetSdk = 36
        versionCode = 73
        versionName = "0.19.4-startup-read"
        manifestPlaceholders["amapAndroidKey"] = localMapKey
        buildConfigField("boolean", "MAP_KEY_CONFIGURED", localMapKey.isNotEmpty().toString())
        buildConfigField("String", "REPOSITORY_URL", "\"$localRepositoryUrl\"")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // Optional local debug identity survives cleanup of the temporary SDK/toolchain.
    val localDebugKey = rootProject.file(".local/signing/debug.keystore")
    if (localDebugKey.isFile) signingConfigs.getByName("debug").storeFile = localDebugKey

    packaging.resources {
        excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        excludes += "META-INF/INDEX.LIST" // Desktop JAR lookup indexes are not used by Android.
        merges += "META-INF/io.netty.versions.properties"
    }

    buildTypes {
        release {
            // First self-use release preserves the existing project-owned private signing identity.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(libs.amap.bundle)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.netty.http)
    implementation(libs.netty.handler)
    implementation(libs.bouncycastle.provider)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}

// Never silently generate another signing identity or ship a map-less self-use release.
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        check(rootProject.file(".local/signing/debug.keystore").isFile) { "Fixed project signing key is required for a compatible release" }
        check(localMapKey.isNotEmpty()) { "A configured Android map key is required for release" }
    }
}
