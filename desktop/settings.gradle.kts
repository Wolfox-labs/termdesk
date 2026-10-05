/**
 * TermDesk desktop — its own Gradle build, NOT added to the Android build.
 *
 * Deliberate: the Android project is shared with other work in flight, and a
 * second module there would mean editing settings.gradle.kts that someone else
 * may be editing too. This build stands alone and reuses the phone's sources by
 * pointing at them (see build.gradle.kts), so nothing in android/ has to change.
 */
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "termdesk-desktop"