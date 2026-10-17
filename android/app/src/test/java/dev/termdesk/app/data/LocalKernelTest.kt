package dev.termdesk.app.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalKernelTest {
    @get:Rule val temp = TemporaryFolder()

    private fun installedFiles(): File = temp.newFolder("files").also { files ->
        File(files, "usr/bin").mkdirs()
        File(files, "usr/bin/bash").writeText("fixture shell")
        File(files, "local-kernel-installed.json").writeText(
            """{"version":"installed-v1","health":"healthy"}""",
        )
    }

    private fun offline() = OkHttpClient.Builder().addInterceptor {
        throw IOException("test computer offline")
    }.build()

    private fun response(code: Int, body: String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun `first metadata failure still discovers a previously installed environment`() = runBlocking {
        val installer = LocalKernelInstaller(installedFiles(), temp.newFolder("cache"), offline())
        assertEquals(LocalKernelStage.UNKNOWN, installer.state.value.stage)
        val result = installer.loadManifest("http://computer.test", "test-token")
        assertTrue(result.installed)
        assertEquals("installed-v1", result.installedVersion)
        assertEquals("healthy", result.healthOutput)
        assertNotNull(result.manifestError)
        assertTrue(result.installedBytes > 0)
    }

    @Test fun `an offline computer does not turn an absent install into a failed install`() = runBlocking {
        val installer = LocalKernelInstaller(temp.newFolder("empty"), temp.newFolder("cache"), offline())
        val result = installer.loadManifest("http://computer.test", "test-token")
        assertEquals(LocalKernelStage.ABSENT, result.stage)
        assertFalse(result.installed)
        assertNotNull(result.manifestError)
    }

    @Test fun `HTTP credential failure preserves the installed environment`() = runBlocking {
        val installer = LocalKernelInstaller(installedFiles(), temp.newFolder("cache"), response(401, "{}"))
        val result = installer.loadManifest("http://computer.test", "test-token")
        assertTrue(result.installed)
        assertEquals("installed-v1", result.installedVersion)
        assertTrue(result.manifestError.orEmpty().contains("401"))
    }

    @Test fun `available package metadata does not conceal an incomplete local install`() = runBlocking {
        val files = temp.newFolder("partial")
        File(files, "usr").mkdirs()
        File(files, "usr/partial").writeText("incomplete extraction")
        val installer = LocalKernelInstaller(files, temp.newFolder("cache"), response(
            200, """{"ok":true,"present":true,"manifest":{"version":"available-v2","package":"runtime.tgz"}}""",
        ))
        val result = installer.loadManifest("http://computer.test", "test-token")
        assertEquals(LocalKernelStage.FAILED, result.stage)
        assertFalse(result.installed)
        assertTrue(result.note.contains("没有安装记录"))
        assertEquals("available-v2", result.manifest?.version)
        assertNull(result.manifestError)
    }

    @Test fun `refresh reads the disk without a computer and keeps available package separate`() = runBlocking {
        val installer = LocalKernelInstaller(installedFiles(), temp.newFolder("cache"), response(
            200, """{"ok":true,"present":true,"manifest":{"version":"available-v2","package":"runtime.tgz"}}""",
        ))
        installer.loadManifest("http://computer.test", "test-token")
        installer.refresh()
        assertTrue(installer.state.value.installed)
        assertEquals("installed-v1", installer.state.value.installedVersion)
        assertEquals("available-v2", installer.state.value.manifest?.version)
    }
}
