// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

internal object OtaUpdater {
    private const val TAG = "DiPlay_OTA"
    private const val SHELL_TMP_DIR = "/data/local/tmp"
    private const val STAGED_APK_NAME = "mg4cplay-update.apk"
    private const val RELEASES = "https://api.github.com/repos/andrew-snape/DiPlay-MG4/releases?per_page=20"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Release(val version: String, val url: String, val fileName: String, val sha256: String)

    fun check(context: Context, callback: (Result<Release?>) -> Unit) = worker.execute {
        val result = runCatching {
            val connection = URL(RELEASES).openConnection() as HttpURLConnection
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MG4CPlay/${version(context)}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            select(JSONArray(body), version(context))
        }
        main.post { callback(result) }
    }

    fun downloadAndInstall(context: Context, release: Release, status: (String) -> Unit) {
        val dm = context.getSystemService(DownloadManager::class.java)
            ?: return status("DownloadManager unavailable")
        // Public Downloads, not the app's own directory: `pm install` runs inside
        // system_server, which SELinux forbids from reading app_data_file. Downloads is the
        // one place both we and the package manager can open. (Same path DiAuto-MG4 uses.)
        val fileName = release.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = prepareDownloadTarget(fileName)
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle("MG4CPlay ${release.version}")
            .setDescription("Downloading update")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
        val id = runCatching { dm.enqueue(request) }.getOrElse { return status("Download failed: ${it.javaClass.simpleName}") }
        status("Downloading ${release.version}…")
        poll(context, dm, id, release, target, status)
    }

    /** Clears any previous copy so DownloadManager does not fall back to "file-1.apk". */
    private fun prepareDownloadTarget(fileName: String): java.io.File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: error("Public Downloads directory unavailable")
        if (!downloads.exists() && !downloads.mkdirs()) error("Could not create Downloads directory")
        val target = java.io.File(downloads, fileName)
        if (target.exists() && !target.delete()) error("Could not replace existing OTA package")
        return target
    }

    private fun poll(context: Context, dm: DownloadManager, id: Long, release: Release, apk: java.io.File, status: (String) -> Unit) {
        main.postDelayed(object : Runnable {
            override fun run() {
                val state = dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (!cursor.moveToFirst()) return@use -1
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (total > 0 && downloaded >= 0) {
                        val percent = (downloaded * 100 / total).coerceIn(0, 100)
                        status("Downloading… $percent% (${downloaded / 1_048_576} / ${total / 1_048_576} MB)")
                    }
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
                when (state) {
                    DownloadManager.STATUS_SUCCESSFUL -> verifyAndInstall(context, release, apk, status)
                    DownloadManager.STATUS_FAILED -> status("Download failed")
                    else -> main.postDelayed(this, 500)
                }
            }
        }, 500)
    }

    private fun verifyAndInstall(context: Context, release: Release, apk: java.io.File, status: (String) -> Unit) {
        status("Verifying update…")
        worker.execute {
            val result = runCatching {
                check(apk.isFile) { "Downloaded APK missing" }
                val digest = apk.inputStream().use(::sha256)
                check(digest.equals(release.sha256, ignoreCase = true)) { "SHA-256 mismatch" }
                verifyPackage(context, apk.absolutePath)
                install(context, stageForPackageManager(apk))
            }
            main.post {
                status(
                    result.fold(
                        { "Update verified; installation started" },
                        { error ->
                            Log.w(TAG, "automatic install unavailable: ${error.message}")
                            offerManualInstall(context, apk)
                        },
                    ),
                )
            }
        }
    }

    /**
     * Sends the driver to the Downloads app to finish the install by hand.
     *
     * This head unit blocks both automatic routes: `pm install` runs in system_server, which
     * SELinux denies every directory we can write to, and the OEM package manager rejects
     * session installs with "not allowed to install non-system apps on internal storage".
     * The package is verified and sitting in Downloads either way, so tapping it there still
     * installs — the same fallback DiAuto-MG4 settled on.
     *
     * @return the status line to show.
     */
    private fun offerManualInstall(context: Context, apk: java.io.File): String {
        val opened = runCatching {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            check(intent.resolveActivity(context.packageManager) != null) { "Downloads app not available" }
            context.startActivity(intent)
            true
        }.getOrElse { error ->
            Log.w(TAG, "could not open Downloads: ${error.message}")
            false
        }
        Toast.makeText(
            context,
            "Automatic install blocked. Tap ${apk.name} in Downloads to install.",
            Toast.LENGTH_LONG,
        ).show()
        return if (opened) {
            "Tap ${apk.name} in Downloads to finish the install."
        } else {
            "Downloaded to Downloads/${apk.name} — open it from the file manager to install."
        }
    }

    /**
     * Copies the verified package to /data/local/tmp, the path `pm install` suggests by name
     * when it refuses anything else.
     *
     * `pm install` hands the path to system_server, and SELinux lets that domain read neither
     * our cache (app_data_file) nor Downloads (sdcardfs). shell_data_file it can read, which
     * is why the manual `adb push /data/local/tmp` route has always worked. The directory is
     * shell-owned and mode 771, so a fresh file may be refused; reusing the existing
     * world-writable name keeps that case working.
     */
    private fun stageForPackageManager(apk: java.io.File): java.io.File {
        val staged = java.io.File(SHELL_TMP_DIR, STAGED_APK_NAME)
        return runCatching {
            apk.inputStream().use { input -> staged.outputStream().use(input::copyTo) }
            check(staged.length() == apk.length()) { "staged copy is truncated" }
            runCatching { staged.setReadable(true, false) }
            Log.i(TAG, "staged ${staged.length()} bytes at ${staged.absolutePath}")
            staged
        }.getOrElse { error ->
            // Fall back to the original path: the session installer streams the bytes
            // itself and does not need the package manager to open this file.
            Log.w(TAG, "could not stage into $SHELL_TMP_DIR: ${error.message}")
            apk
        }
    }

    private fun verifyPackage(context: Context, path: String) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
            ?: error("Invalid APK")
        check(archive.packageName == context.packageName) { "Package name mismatch" }
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        fun certificates(info: android.content.pm.PackageInfo): Set<String> {
            val signers = info.signingInfo?.apkContentsSigners?.toList()
                ?.takeIf { it.isNotEmpty() }
                ?: info.signatures?.toList().orEmpty()
            check(signers.isNotEmpty()) { "Signing certificate unavailable" }
            return signers.map { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val expected = certificates(installed)
        val actual = certificates(archive)
        check(expected == actual) { "Signing certificate mismatch" }
    }

    private fun install(context: Context, apk: java.io.File) {
        // MG4 head unit: the OEM PackageManager refuses every session-based install with
        // "Not allowed to install non-system apps on internal storage" — even under
        // android.uid.system and even for an app already living in /data/app. The only
        // working path is `pm install -f`, where -f ("install on internal flash") pins
        // the volume and bypasses the OEM check in PackageHelper.resolveInstallVolume.
        // Session install stays as the fallback for non-system (phone) builds.
        if (Process.myUid() == Process.SYSTEM_UID) {
            try {
                installWithPm(apk)
                Log.i(TAG, "pm install ok: ${apk.absolutePath} size=${apk.length()}")
                apk.delete()
                main.post { Toast.makeText(context, "MG4CPlay updated", Toast.LENGTH_LONG).show() }
                return
            } catch (t: Throwable) {
                Log.w(TAG, "pm install failed, trying PackageInstaller session: ${t.message}")
            }
        }
        installWithSession(context, apk)
    }

    private fun installWithPm(apk: java.io.File) {
        // -r replace, -f force internal flash (bypasses MG4 volume policy),
        // -d allow downgrade (test builds), -t allow test packages.
        val process = ProcessBuilder("pm", "install", "-r", "-f", "-d", "-t", apk.absolutePath)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val code = process.waitFor()
        Log.i(TAG, "pm install exit=$code output=$output")
        if (code != 0 || !output.lowercase().contains("success")) {
            throw IllegalStateException(if (output.isEmpty()) "pm install exit $code" else output)
        }
    }

    private fun installWithSession(context: Context, apk: java.io.File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(context.packageName)
                // PackageManager.INSTALL_LOCATION_INTERNAL_ONLY (=1) is not in the public SDK.
                setInstallLocation(1)
            }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input -> session.openWrite("MG4CPlay.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val intent = Intent(context, OtaInstallReceiver::class.java).setAction("com.shihab.diplay.OTA_RESULT")
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            session.commit(pending.intentSender)
        }
    }

    private fun select(releases: JSONArray, current: String): Release? {
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val version = release.optString("tag_name").trimStart('v', 'V')
            if (compare(version, current) <= 0) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                if (!name.startsWith("MG4CPlay-") || !name.endsWith(".apk")) continue
                val digest = asset.optString("digest").removePrefix("sha256:")
                if (!digest.matches(Regex("[a-fA-F0-9]{64}"))) continue
                return Release(version, asset.getString("browser_download_url"), name, digest)
            }
        }
        return null
    }

    private fun compare(left: String, right: String): Int {
        fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
        val a = parts(left); val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val result = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun version(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

class OtaInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirmation != null) context.startActivity(confirmation)
            return
        }
        Toast.makeText(context, if (status == PackageInstaller.STATUS_SUCCESS) "MG4CPlay updated" else "Update install failed", Toast.LENGTH_LONG).show()
    }
}
