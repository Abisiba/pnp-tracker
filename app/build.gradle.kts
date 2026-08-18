plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
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
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
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
        // Generated sources (e.g. the Compose resources `Res` class) are not ours to format.
        exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
    }
}
