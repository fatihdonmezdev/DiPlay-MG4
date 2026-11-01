package com.shilapi.xcertplay

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.MessageDigest

/**
 * Temporary (mg4.85): copies the compiled code of the stock AllGo apps into the diagnostic
 * report, so it can be read without ADB.
 *
 * The SWI165 system apps were compiled ahead of time: the exported APK of
 * `com.allgo.remoteui.mediabrowserservice` has no `classes.dex`, and its code lives beside
 * it as `oat/<abi>/<name>.vdex` / `.odex`. The cluster reads CarPlay song details from that
 * app's media session (docs/MG4_SWI165_CLUSTER_MEDIA_NOTES.md), and its exported
 * `...RemoteUIMediaBrowserServiceInterface` is probably how the stock stack feeds it. The
 * interface's transaction codes are in these files.
 *
 * Read-only: files are only read, never changed. Each file is written as base64 between
 * markers, with its size and SHA-256 so the copy can be checked after decoding.
 */
internal object SystemAppCodeExport {
    val PACKAGES = listOf(
        "com.allgo.remoteui.mediabrowserservice",
        "com.allgo.rui",
    )
    private val EXTENSIONS = setOf("vdex", "odex", "dex", "art")
    private const val MAX_FILE_BYTES = 6L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 12L * 1024 * 1024

    fun appendTo(report: StringBuilder, context: Context) {
        report.appendLine("--- Stock app code (temporary, mg4.85) ---")
        var total = 0L
        for (pkg in PACKAGES) {
            val dir = runCatching {
                File(context.packageManager.getApplicationInfo(pkg, 0).sourceDir).parentFile
            }.getOrNull()
            if (dir == null) {
                report.appendLine("$pkg: not installed")
                continue
            }
            val files = codeFiles(dir)
            report.appendLine("$pkg: ${dir.path} files=${files.size}")
            for (file in files) {
                val size = file.length()
                val label = file.relativeTo(dir).path
                if (size > MAX_FILE_BYTES || total + size > MAX_TOTAL_BYTES) {
                    report.appendLine("SKIPPED $pkg/$label size=$size (over the size limit)")
                    continue
                }
                val bytes = runCatching { file.readBytes() }.getOrElse {
                    report.appendLine("UNREADABLE $pkg/$label size=$size ${it.javaClass.simpleName}")
                    null
                } ?: continue
                total += bytes.size
                report.appendLine("BEGIN-FILE $pkg/$label size=${bytes.size} sha256=${sha256(bytes)}")
                report.append(Base64.encodeToString(bytes, Base64.DEFAULT))
                report.appendLine("END-FILE $pkg/$label")
            }
        }
        report.appendLine()
    }

    /** Ahead-of-time code beside the APK (vdex, odex and art files under `oat`), plus any loose dex. */
    internal fun codeFiles(dir: File): List<File> =
        dir.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            .sortedBy { it.path }
            .toList()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
