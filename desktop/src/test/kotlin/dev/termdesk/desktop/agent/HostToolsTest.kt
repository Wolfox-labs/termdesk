package dev.termdesk.desktop.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HostToolsTest {
    @Test
    fun packagedAgentIsFoundBesideInstalledApplicationJar() {
        val directory = Files.createTempDirectory("termdesk-installed-app").toFile()
        try {
            val jar = File(directory, "app/TermDesk.jar").apply {
                parentFile.mkdirs()
                writeText("")
            }
            File(directory, "app/resources/pc-agent/src/server.js").apply {
                parentFile.mkdirs()
                writeText("")
            }
            assertEquals(File(directory, "app/resources").absoluteFile, packagedAgentRoot(jar)?.absoluteFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun nodeCanBeFoundOnWindowsPathWithoutDriveAssumptions() {
        val directory = Files.createTempDirectory("termdesk-node").toFile()
        try {
            val node = File(directory, "node.exe").apply { writeText("") }
            val found = findNodeExecutable(
                environment = mapOf("Path" to directory.absolutePath, "PATHEXT" to ".EXE;.CMD"),
                osName = "Windows 11",
            )
            assertEquals(node.absoluteFile, found?.absoluteFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun nodeInstallOverrideAndWindowsInstallDirectoryAreSupported() {
        val directory = Files.createTempDirectory("termdesk-node-home").toFile()
        try {
            val override = File(directory, "custom-node.exe").apply { writeText("") }
            val install = File(directory, "nodejs/node.exe").apply {
                parentFile.mkdirs()
                writeText("")
            }
            assertEquals(
                override.absoluteFile,
                findNodeExecutable(mapOf("TERMDESK_NODE" to override.path), "Windows 11")?.absoluteFile,
            )
            assertEquals(
                install.absoluteFile,
                findNodeExecutable(mapOf("ProgramFiles" to directory.path), "Windows 11")?.absoluteFile,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun taskkillUsesWindowsRootAndThenFallsBackToPath() {
        val directory = Files.createTempDirectory("termdesk-system").toFile()
        try {
            val systemKill = File(directory, "System32/taskkill.exe").apply {
                parentFile.mkdirs()
                writeText("")
            }
            val pathKill = File(directory, "bin/taskkill.exe").apply {
                parentFile.mkdirs()
                writeText("")
            }
            assertEquals(
                systemKill.absoluteFile,
                findWindowsTaskkill(mapOf("WINDIR" to directory.path), ";")?.absoluteFile,
            )
            assertEquals(
                pathKill.absoluteFile,
                findWindowsTaskkill(mapOf("Path" to pathKill.parentFile.path), ";")?.absoluteFile,
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
