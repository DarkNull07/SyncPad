import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The public signing key lives outside the repository. Build with -Ppublic to use it;
// otherwise the APK is signed with this machine's debug key.
val signing = Properties().apply {
    val f = File(System.getProperty("user.home"), ".syncpad/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val publicBuild = project.hasProperty("public")

android {
    if (publicBuild) signingConfigs.create("public") {
        storeFile = File(signing.getProperty("store") ?: error("No ~/.syncpad/signing.properties"))
        storePassword = signing.getProperty("password")
        keyAlias = signing.getProperty("alias")
        keyPassword = signing.getProperty("password")
    }
    namespace = "dev.syncpad.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.syncpad.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (publicBuild) "public" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }
    // iroh ships a library per processor. Android 9 phones are 64-bit ARM; on anything else the
    // library is missing, the app still works, and only "Connect from anywhere" is unavailable.
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
    packaging {
        jniLibs.useLegacyPackaging = true          // compressed in the APK: a much smaller download
        resources.excludes += listOf("darwin-*/**", "linux-*/**", "win32-*/**")      // iroh's desktop libraries
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
                it.systemProperty("syncpad.host", rootDir.resolve("../host").canonicalPath)
                it.systemProperty("syncpad.shots", rootDir.resolve("app/build/shots").path)
                it.systemProperty("syncpad.site", project.hasProperty("site").toString())
                it.systemProperty("syncpad.net", project.hasProperty("net").toString())
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.maxHeapSize = "2g"
                it.testLogging {
                    events("passed", "failed", "standardOut")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("computer.iroh:iroh-android:1.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("net.java.dev.jna:jna:5.15.0")          // iroh on this computer, for the tunnel test
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
