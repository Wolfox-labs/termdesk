import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/**
 * TermDesk desktop shell.
 *
 * Two rules shaped this file:
 *
 * 1. **One visual language.** The phone app's theme (`Theme.kt`, `Monokai.kt`)
 *    is compiled here VERBATIM from the Android source tree via an extra
 *    `kotlin.srcDir`. Adding it to the build is what makes the desktop window
 *    structurally incapable of drifting from the phone's palette, radii and
 *    type scale — there is no second copy to keep in sync. Nothing under
 *    android/ is modified; the directory is only read.
 *
 * 2. **Native window, no browser.** Compose Desktop renders through Skia, so
 *    this is a real desktop window, and the same code can be packaged into an
 *    installer later (`packageMsi`) without rewriting the UI.
 */
plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    // The agent's JSON endpoints are read with typed DTOs, not string scraping.
    kotlin("plugin.serialization") version "2.0.21"
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDirs(
            "src/main/kotlin",
            // The phone's palette, used as-is: one source of truth for both UIs.
            "../android/app/src/main/java/dev/termdesk/app/ui/theme",
        )
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

compose.desktop {
    application {
        mainClass = "dev.termdesk.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "TermDesk"
            packageVersion = "0.2.0"
            description = "TermDesk — 手机远程指挥这台电脑上的 agent 内核"
            vendor = "Wolfox Labs"
        }
    }
}