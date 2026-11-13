// Pure Kotlin/JVM: the camera-file parser and every rule that can be tested without a phone.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.serialization.json)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}

tasks.test {
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
