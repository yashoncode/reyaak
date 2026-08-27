import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing credentials live in keystore.properties, which is gitignored along
// with the .jks itself. Absent, the release build simply goes unsigned rather
// than failing: a fresh clone must still be able to compile the project.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storeFile") != null &&
    rootProject.file(keystoreProps.getProperty("storeFile")).exists()

// The Android host, and nothing more: an Activity, an Application, a foreground
// service, a boot receiver, and the Keystore. Every screen except the agent
// runtime control panel now lives in :ui, so this module is what an :iosApp
// module will mirror rather than what it will duplicate.
android {
    namespace = "io.reyaak"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.reyaak"
        minSdk = 26

        // targetSdk is deliberately held at 34. Reyaak's whole purpose is a
        // long-lived background agent, and API 35+ enforces a 6h/24h cap on
        // dataSync foreground services plus tighter boot-completed FGS rules.
        // 34 is the most permissive still-supported regime. Raise it, and add
        // WorkManager-based chunked execution, only if this ships on Play.
        targetSdk = 34

        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 built-in Kotlin: compilerOptions live inside android {}.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            )
        }
    }

    // buildConfig is off by default in AGP 9; the Settings screen shows the
    // version, which the host reads and passes into shared UI.
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(project(":ui"))

    // Compose, Material 3, and the ViewModel APIs arrive transitively as :ui's
    // api dependencies, so there is no second Compose version list to drift.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
