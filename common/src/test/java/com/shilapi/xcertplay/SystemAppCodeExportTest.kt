package com.shilapi.xcertplay

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemAppCodeExportTest {
    @Test
    fun findsAheadOfTimeCodeBesideTheApkButNotTheApkOrResources() {
        val dir = Files.createTempDirectory("mbs").toFile()
        try {
            File(dir, "RemoteUIMediaBrowserService.apk").writeText("apk")
            File(dir, "oat/arm64").mkdirs()
            File(dir, "oat/arm64/RemoteUIMediaBrowserService.vdex").writeText("vdex")
            File(dir, "oat/arm64/RemoteUIMediaBrowserService.odex").writeText("odex")
            File(dir, "oat/arm").mkdirs()
            File(dir, "oat/arm/RemoteUIMediaBrowserService.ODEX").writeText("odex32")
            File(dir, "lib/arm64").mkdirs()
            File(dir, "lib/arm64/libnative.so").writeText("so")

            val found = SystemAppCodeExport.codeFiles(dir).map { it.relativeTo(dir).path }

            assertEquals(
                listOf(
                    "oat/arm/RemoteUIMediaBrowserService.ODEX",
                    "oat/arm64/RemoteUIMediaBrowserService.odex",
                    "oat/arm64/RemoteUIMediaBrowserService.vdex",
                ),
                found,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
