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
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (publicBuild) "public" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("syncpad.host", rootDir.resolve("../host").canonicalPath)
                it.systemProperty("syncpad.shots", rootDir.resolve("app/build/shots").path)
                it.systemProperty("syncpad.site", project.hasProperty("site").toString())
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
