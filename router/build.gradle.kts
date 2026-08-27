plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

// :router holds every provider-specific line in the project, and nothing else
// may. Keeping it in commonMain is what enforces that now: commonMain has no
// Android SDK and no JVM stdlib on its classpath, so an OS or UI concern cannot
// leak in even by accident.
kotlin {
    // jvm() exists for the test suite. Running the routing, scoring, and config
    // tests on a plain JVM keeps them a few seconds long, with no emulator and
    // no Robolectric, and it is the only target that can actually execute on
    // a Windows host.
    jvm()

    android {
        namespace = "io.reyaak.router"
        compileSdk = 37
        minSdk = 26
    }

    // No iosX64: Compose Multiplatform 1.12 stopped publishing the Intel
    // simulator target, and simulators run arm64 on every supported Mac.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
        }
        // The client is built without naming an engine, so each target resolves
        // the single one on its own classpath. That is what lets one adapter
        // implementation serve both platforms.
        val okhttpEngine = libs.ktor.client.okhttp
        jvmMain.dependencies { implementation(okhttpEngine) }
        androidMain.dependencies { implementation(okhttpEngine) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }

        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
