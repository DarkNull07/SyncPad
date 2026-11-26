pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
// Lets Gradle fetch the Java 21 the tests need (iroh's Kotlin library is built for it).
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "SyncPad"
include(":app")
