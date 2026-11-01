package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.hud.BydAppleManeuver
import com.shilapi.xcertplay.hud.BydHudRouteChange
import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * CarPlay turn-by-turn on the MG4 SWI165 instrument cluster.
 *
 * Read from the car's own APKs (SaicAdapterService_eh32_aus, ClusterInteraction_eh32_aus_local_P):
 * the cluster's navigation slot is fed by `com.saicmotor.adapterservice`. The car's sat-nav pushes
 * guidance into its exported `MapService` (`IMapService`, no permission), which relays it to the
 * cluster app (`GeneralService` listeners) and to the cluster ECU (`ClusterManager`). DiPlay pushes
 * CarPlay's guidance through the same calls:
 *
 * - `guideStatusChange(int)` 29: 1 guiding (the cluster shows its navigation card), 0 ended.
 * - `guideInfosChange(int icon, int metres, String road)` 30: next turn.
 * - `roadInfoChange(String)` 31, `remainingTimesChange(int minutes)` 38,
 *   `remainingDistanceChange(int metres)` 39, `isMapNavigating(boolean)` 22.
 *
 * Icons are the cluster's default (EU/UK, `ic_tbt_N`) set; see [icon].
 */
object Mg4ClusterNavigation {
    private const val PACKAGE = "com.saicmotor.adapterservice"
    private const val SERVICE = "com.saicmotor.adapterservice.services.MapService"
    private const val DESCRIPTOR = "com.saicmotor.adapterservice.IMapService"
    private const val TX_IS_MAP_NAVIGATING = 22
    private const val TX_GUIDE_STATUS = 29
    private const val TX_GUIDE_INFOS = 30
    private const val TX_ROAD_INFO = 31
    private const val TX_REMAINING_TIMES = 38
    private const val TX_REMAINING_DISTANCE = 39
    private const val TICK_MILLIS = 1_000L

    // Lazy so the pure [icon] mapping can be used (and unit tested) without starting a thread.
    private val handler by lazy { Handler(HandlerThread("mg4-cluster-nav").apply { start() }.looper) }
    private val route = BydHudRouteState()
    @Volatile private var binder: IBinder? = null
    private var bound = false
    private var guiding = false
    private var lastSent: BydAppleManeuver? = null
    private var lastRoad: String? = null
    private var report: (String) -> Unit = {}

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            handler.post { report("Cluster nav: adapter service connected") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            handler.post { guiding = false; lastSent = null }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            publish()
            if (bound) handler.postDelayed(this, TICK_MILLIS)
        }
    }

    /** Binds the adapter service for one CarPlay session. */
    fun start(context: Context, log: (String) -> Unit) = handler.post {
        report = log
        if (bound) return@post
        val ok = runCatching {
            context.applicationContext.bindService(
                Intent().setComponent(ComponentName(PACKAGE, SERVICE)),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        bound = ok
        report(if (ok) "Cluster nav: binding adapter MapService" else "Cluster nav: adapter MapService not available")
        if (ok) handler.postDelayed(tick, TICK_MILLIS)
    }

    /** Route guidance frames (0x5201/0x5202) from the iPhone. */
    fun onFrame(frame: Iap2Frame) {
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        handler.post {
            if (route.accept(frame.messageId, frame.payload) != BydHudRouteChange.NONE) publish()
        }
    }

    /** Ends guidance on the cluster and unbinds. */
    fun stop(context: Context) = handler.post {
        route.clear()
        publish()
        handler.removeCallbacks(tick)
        if (bound) runCatching { context.applicationContext.unbindService(connection) }
        bound = false
        binder = null
    }

    private fun publish() {
        val service = binder?.takeIf { it.isBinderAlive } ?: return
        val maneuver = route.currentApple()
        if (maneuver == null) {
            if (guiding) {
                call(service, TX_GUIDE_STATUS) { writeInt(0) }
                call(service, TX_IS_MAP_NAVIGATING) { writeInt(0) }
                guiding = false
                lastSent = null
                lastRoad = null
                report("Cluster nav: guidance ended")
            }
            return
        }
        if (!guiding) {
            call(service, TX_IS_MAP_NAVIGATING) { writeInt(1) }
            val ok = call(service, TX_GUIDE_STATUS) { writeInt(1) }
            guiding = true
            report("Cluster nav: guidance started sent=$ok")
        }
        if (maneuver == lastSent) return
        val icon = icon(maneuver.type, maneuver.drivingSide)
        val ok = call(service, TX_GUIDE_INFOS) {
            writeInt(icon)
            writeInt(maneuver.distanceMeters)
            writeString(maneuver.road)
        }
        if (maneuver.road != lastRoad) {
            call(service, TX_ROAD_INFO) { writeString(maneuver.road) }
            lastRoad = maneuver.road
        }
        maneuver.remainingMeters?.let { metres ->
            call(service, TX_REMAINING_DISTANCE) { writeInt(metres.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }
        }
        maneuver.remainingSeconds?.let { seconds ->
            call(service, TX_REMAINING_TIMES) { writeInt(((seconds + 59) / 60).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }
        }
        // Log turn changes, not every distance tick.
        if (lastSent?.type != maneuver.type || lastSent?.road != maneuver.road) {
            report("Cluster nav: apple=${maneuver.type} side=${maneuver.drivingSide} icon=$icon " +
                "distance=${maneuver.distanceMeters}m road=\"${maneuver.road}\" sent=$ok")
        }
        lastSent = maneuver
    }

    private inline fun call(service: IBinder, code: Int, write: Parcel.() -> Unit): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            service.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (_: Exception) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Apple iAP2 RouteGuidanceManeuverType -> the cluster's `ic_tbt_N` icon, from the icons in
     * ClusterInteraction: 0 straight, 1 left, 2 right, 3 slight left, 4 slight right, 5 U-turn
     * left, 6 U-turn right, 7 sharp right, 8 sharp left, 9 roundabout straight on, 10 roundabout
     * left, 11 roundabout right, 12 roundabout U-turn, 17 keep left, 18 keep right, 19 ferry,
     * 20 destination, 21 destination on the left, 22 on the right, 32/33 slip road joining,
     * 36/37 exit left/right. [drivingSide] 1 is left-hand traffic (Australia).
     */
    internal fun icon(appleType: Int, drivingSide: Int): Int {
        val leftHand = drivingSide == 1
        if (appleType in 28..46) {
            // Roundabout exit N: first exit is the near side.
            return when (appleType - 27) {
                1 -> if (leftHand) 10 else 11
                2 -> 9
                3 -> if (leftHand) 11 else 10
                else -> 12
            }
        }
        return when (appleType) {
            1, 20 -> 1
            2, 21 -> 2
            49 -> 3
            50 -> 4
            47 -> 8
            48 -> 7
            4, 18, 26 -> if (leftHand) 6 else 5
            19 -> 12
            6, 7 -> 9
            13, 52 -> 17
            14, 53 -> 18
            22 -> 36
            23 -> 37
            8 -> if (leftHand) 36 else 37
            9 -> if (leftHand) 32 else 33
            15, 16, 17 -> 19
            10, 12, 27 -> 20
            24 -> 21
            25 -> 22
            else -> 0
        }
    }
}
