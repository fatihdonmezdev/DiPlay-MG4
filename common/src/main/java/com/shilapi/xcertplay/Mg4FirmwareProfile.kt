package com.shilapi.xcertplay

/**
 * Per-firmware behaviour for MG4 head units, chosen from `ro.build.mt2712.version`.
 *
 * Each MG4 firmware generation ships a different stock launcher and video stack, so the
 * launcher shortcut and the video renderer are picked here instead of being hard-coded to
 * one generation. Unknown firmware keeps the original DiPlay behaviour and never creates
 * the launcher overlay.
 */
internal data class Mg4FirmwareProfile(
    val generation: Generation,
    /** Raw firmware string, e.g. `SWI165-29176-1100R11`, or null if the property is absent. */
    val firmware: String?,
    /** Package of the stock SAIC home screen on this firmware. */
    val stockLauncherPackage: String?,
    /** Touch target over the stock launcher's Apple CarPlay card, or null when not measured. */
    val launcherCarPlayCard: LauncherCard?,
    /**
     * Render CarPlay video into a SurfaceView (hardware overlay plane, the same path as the
     * stock AllGo CarPlay app) instead of a TextureView (GPU composited) by default.
     */
    val preferSurfaceVideo: Boolean,
) {
    enum class Generation { SWI69, SWI165, OTHER }

    /** Window position and size in pixels, relative to the launcher's content area. */
    data class LauncherCard(val x: Int, val y: Int, val width: Int, val height: Int)

    val isMg4: Boolean get() = generation != Generation.OTHER

    companion object {
        const val FIRMWARE_PROPERTY = "ro.build.mt2712.version"

        // MG4 Comfort 1920x720: stock card is x=1468..1878, y=95..689.
        // Window coordinates start after the 142 px launcher navigation rail.
        private val SWI69_CARPLAY_CARD = LauncherCard(x = 1326, y = 88, width = 410, height = 298)

        fun forFirmware(firmware: String?): Mg4FirmwareProfile {
            val value = firmware?.trim()?.takeIf(String::isNotEmpty)
            return when {
                value == null -> other(null)
                value.startsWith("SWI165") -> Mg4FirmwareProfile(
                    generation = Generation.SWI165,
                    firmware = value,
                    stockLauncherPackage = "com.saicmotor.hmi.launcher",
                    // The SWI165 launcher is a different app from SWI69's. Its CarPlay card has
                    // not been measured, so the overlay stays off rather than guessing and
                    // stealing taps from another card. Many SWI165 owners replace the home
                    // screen (MG4Dash) anyway, in which case the stock card is never shown.
                    launcherCarPlayCard = null,
                    // The stock AllgoCarplay_EH32 app decodes into a SurfaceView. The TextureView
                    // path looks washed out next to it on this unit.
                    preferSurfaceVideo = true,
                )
                value.startsWith("SWI69") -> Mg4FirmwareProfile(
                    generation = Generation.SWI69,
                    firmware = value,
                    stockLauncherPackage = "com.saicmotor.launcher",
                    launcherCarPlayCard = SWI69_CARPLAY_CARD,
                    preferSurfaceVideo = false,
                )
                else -> other(value)
            }
        }

        @Volatile private var detected: Mg4FirmwareProfile? = null

        /** The running head unit's profile, read once per process. */
        fun detect(): Mg4FirmwareProfile =
            detected ?: forFirmware(readSystemProperty(FIRMWARE_PROPERTY)).also { detected = it }

        private fun other(firmware: String?) = Mg4FirmwareProfile(
            generation = Generation.OTHER,
            firmware = firmware,
            stockLauncherPackage = null,
            launcherCarPlayCard = null,
            preferSurfaceVideo = false,
        )

        private fun readSystemProperty(key: String): String? = runCatching {
            val properties = Class.forName("android.os.SystemProperties")
            val get = properties.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, key, "") as? String)?.takeIf(String::isNotBlank)
        }.getOrNull()
    }
}
