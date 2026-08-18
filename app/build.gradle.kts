plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
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
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.sqlite.bundled)
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

ktlint {
    filter {
        // Generated sources (Compose resources, Room and KSP output) are not ours to format.
        exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
    }
}
