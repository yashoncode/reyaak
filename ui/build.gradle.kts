plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}

// Every screen lives here, and this module has no androidMain or iosMain source
// set at all. That is deliberate: the two things the screens genuinely need from
// an OS, opening a URL and picking a file, arrive as lambdas from the host,
// and the one screen that is really an OS control panel (the agent runtime) is a
// composable slot. So there is nothing to reimplement for iOS beyond the host.
kotlin {
    android {
        namespace = "io.reyaak.ui"
        compileSdk = 37
        minSdk = 26
    }

    // No iosX64: Compose Multiplatform 1.12 stopped publishing the Intel
    // simulator target, and simulators run arm64 on every supported Mac.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))

            // api, not implementation: the host module composes these screens
            // and needs the same Compose and ViewModel APIs on its own
            // classpath. Exposing them here is what keeps :app's build file
            // free of a second, drift-prone Compose dependency list.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)

            api(libs.androidx.lifecycle.viewmodel)
            api(libs.androidx.lifecycle.viewmodel.compose)
            api(libs.androidx.lifecycle.runtime.compose)
        }
    }

    compilerOptions {
        // Material 3 still marks its scaffolding APIs experimental, TopAppBar
        // among them, so using M3 at all means opting in. Declared once rather
        // than annotating every composable that touches one.
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            // flatMapLatest, used to swap the message flow when the conversation changes.
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}
