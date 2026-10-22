import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Sync

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

val packagedAgentResources = layout.buildDirectory.dir("generated/desktop-agent-resources")
val stageAgentResources = tasks.register<Sync>("stageAgentResources") {
    doFirst {
        val requiredModules = listOf("ws", "qrcode", "socks-proxy-agent")
        val missingModules = requiredModules.filterNot {
            file("../pc-agent/node_modules/$it/package.json").isFile
        }
        if (missingModules.isNotEmpty()) {
            throw GradleException(
                "Desktop distribution needs pc-agent production dependencies. " +
                    "Run `npm ci --omit=dev` in pc-agent; missing: ${missingModules.joinToString()}",
            )
        }
    }
    from("../pc-agent") {
        include("package.json")
        include("src/**")
        include("kernels/**")
        include("node_modules/**")
        exclude("node_modules/.bin/**")
        exclude("node_modules/jsqr/**")
        into("common/pc-agent")
    }
    into(packagedAgentResources)
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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "dev.termdesk.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            // Compose's default image omits HttpClient and modules used by Skiko.
            // Keep in sync with `tools/verify-desktop-package.mjs` / jdeps output.
            modules("java.net.http", "java.instrument", "jdk.unsupported")
            // The native app image carries the same agent source and installed
            // production dependencies used by the repository launcher.
            appResourcesRootDir.set(packagedAgentResources)
            packageName = "TermDesk"
            // Numeric on purpose: the installer format only accepts MAJOR.MINOR.BUILD, so a
            // suffix on the product version cannot live here; the consistency check compares
            // this with the numeric core of the one product version.
            packageVersion = "1.1.0"
            description = "TermDesk — 手机远程指挥这台电脑上的 agent 内核"
            vendor = "Wolfox Labs"
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(stageAgentResources)
}
