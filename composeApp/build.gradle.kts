import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.materialIconsExtended)
            implementation(compose.uiTooling)
            implementation(libs.kotlinx.coroutinesSwing)

            // HTTP — same OkHttp as Android, works unmodified on JVM
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

            // JSON — same Gson + org.json as Android (Android bundles org.json
            // via the SDK; on JVM we pull it in explicitly).
            implementation("com.google.code.gson:gson:2.11.0")
            implementation("org.json:json:20240303")

            // Coroutines core (coroutines-swing already provided above)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

            // SQLite JDBC — binary-compatible .db format with Android Room.
            // Deviation from PDF plan: no Room / no KSP (KSP not yet
            // released for Kotlin 2.3.20). DAOs are hand-written JDBC wrappers
            // over the same schema. See docs/DESKTOP_PORT_PLAN.md §0 / §16.
            implementation("org.xerial:sqlite-jdbc:3.46.1.3")

            // JNA for Windows Hello + autostart registry writes
            implementation("net.java.dev.jna:jna-platform:5.14.0")

            // Logging — SLF4J replaces android.util.Log
            implementation("org.slf4j:slf4j-simple:2.0.13")
        }
    }
}


compose.desktop {
    application {
        mainClass = "com.itconnect.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName    = "IT Connect"
            packageVersion = "1.0.0"
            description    = "Remote Windows control client"
            vendor         = "IT Connect"

            windows {
                menuGroup       = "IT Connect"
                // FIXED — installer upgrade path depends on this; never change.
                upgradeUuid     = "8e9f1a2c-3b4d-4e5f-8a9b-0c1d2e3f4567"
                perUserInstall  = true
                shortcut        = true
                dirChooser      = true
                // iconFile.set(project.file("src/jvmMain/resources/icon.ico"))  // add when asset ships
            }
        }
    }
}
