plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    // jvm() exists for the test suite, as in :router: the agent gate and the
    // context builder are plain logic, and a plain JVM runs them in seconds
    // with no emulator, the only target that can execute on a Windows host.
    jvm()

    android {
        namespace = "io.reyaak.core"
        compileSdk = 37
        minSdk = 26
    }

    // No iosX64: Compose Multiplatform 1.12 stopped publishing the Intel
    // simulator target, and simulators run arm64 on every supported Mac.
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        // Room's generated ReyaakDatabaseConstructor is an `actual object`, and
        // expect/actual classes are still flagged Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }

        commonMain.dependencies {
            api(project(":router"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            api(libs.androidx.room.runtime)
            // The bundled driver ships its own SQLite build, so Android and iOS
            // run the same engine at the same version. Using the platform's
            // SQLite instead would mean the agent's memory behaves differently
            // depending on the OS release it happens to be sitting on.
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

// Room's KSP processor runs once per target: each one gets its own generated
// DAO implementation and its own actual for the database constructor.
dependencies {
    listOf("kspJvm", "kspAndroid", "kspIosArm64", "kspIosSimulatorArm64").forEach {
        add(it, libs.androidx.room.compiler)
    }
}

// Schemas are exported so a migration can be diffed in review. This database
// holds the agent's memory, so a schema change is a thing to read, not to trust.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
