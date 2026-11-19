import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.unroll"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.uranashel.unroll"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
    }

    // Release key lives outside git: android/keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
    val keyProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (keyProps != null) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keyProps != null) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.exifinterface)
    // Installs src/main/baseline-prof.txt on release installs (AOT-compiles the hot paths).
    implementation(libs.profileinstaller)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.billing)
    testImplementation(libs.junit)
}
