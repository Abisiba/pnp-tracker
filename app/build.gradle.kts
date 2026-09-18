plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(libs.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
            // The backup document is a common concern: PLAN 14.4.1 has one JSON
            // contract, written and read by shared code, so the library sits here
            // rather than in the desktop source set.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.sqlite.bundled)
                // Reading .xlsx is a JVM concern; the snapshot model it produces is common.
                implementation(libs.poi.ooxml)
            }
        }
    }
}

dependencies {
    // Only the desktop target is generated for; the general `ksp(...)` configuration is deprecated.
    add("kspDesktop", libs.room.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.pnptracker.resources"
}

compose.desktop {
    application {
        mainClass = "dev.pnptracker.MainKt"
    }
}

// A real window on this desktop: the application is started with temporary XDG
// folders and its one main window is closed by the test sources' verified closer.
// It needs a display and opens a window, so it is never part of `check`.
tasks.register<JavaExec>("desktopWindowSmoke") {
    group = "verification"
    description = "Opens the application with temporary folders and closes its own window, and only that one."
    val desktopTest = kotlin.jvm("desktop").compilations.getByName("test")
    classpath = files(desktopTest.output.allOutputs, desktopTest.runtimeDependencyFiles)
    mainClass = "dev.pnptracker.platform.desktop.DesktopWindowSmokeKt"
    // `-PsmokeScenario=damaged-database` starts it on a damaged database (PLAN 14.7.4).
    providers.gradleProperty("smokeScenario").orNull?.let { args(it) }
}

ktlint {
    filter {
        // Generated sources (Compose resources, Room and KSP output) are not ours to format.
        exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
    }
}
