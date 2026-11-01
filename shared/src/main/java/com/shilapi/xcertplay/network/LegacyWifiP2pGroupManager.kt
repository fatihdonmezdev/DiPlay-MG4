package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.HandlerThread
import android.os.Looper
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Direct group owner for Android 9 (API 28) head units such as the MG4 on SWI165.
 *
 * Android 9 has no public way to choose the group's name, passphrase or channel, so this uses
 * the two-argument [WifiP2pManager.createGroup] (system-generated DIRECT-xx credentials) and
 * the hidden `setWifiP2pChannels` call to steer the operating channel. Both need the system UID
 * that the MG4 build runs as. The channel actually used is read back from the driver; it is never
 * assumed from the request.
 */
// The MG4 build is platform-signed (android.uid.system), which exempts it from hidden-API limits.
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
class LegacyWifiP2pGroupManager(
    context: Context,
    private val band: ManualHotspotBand,
    private val diagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val p2pManager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: throw IllegalStateException("WifiP2pManager is unavailable")
    private val ownership = appContext.getSharedPreferences("carplay_wifi_p2p", Context.MODE_PRIVATE)
    private val lock = Object()

    private var thread: HandlerThread? = null
    private var channel: WifiP2pManager.Channel? = null
    private var groupName: String? = null
    private var groupNetworkId: Int? = null
    private var channelsSteered = false
    @Volatile private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LegacyWifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            val wifi = appContext.getSystemService(WifiManager::class.java)
            val wifiEnabled = runCatching { wifi?.isWifiEnabled }.getOrNull()
            val fiveGhz = runCatching { wifi?.is5GHzBandSupported }.getOrNull()
            val station = stationFrequency(wifi)
            diagnostic("Wi-Fi P2P legacy preflight wifiEnabled=$wifiEnabled fiveGhzSupported=${fiveGhz ?: "unknown"} " +
                "stationMHz=${station ?: "none"} band=$band")
            if (wifiEnabled == false) throw IOException("Turn on Wi-Fi in the car's settings, then reconnect")

            val handlerThread = HandlerThread("xcertplay-wifi-p2p-legacy").apply { start() }
            val p2pChannel = p2pManager.initialize(appContext, handlerThread.looper) {
                diagnostic("Wi-Fi P2P legacy channel disconnected")
            } ?: throw IOException("Wi-Fi Direct is unavailable")
            synchronized(lock) {
                ensureOpen()
                thread = handlerThread
                channel = p2pChannel
            }

            reclaimExistingGroup(p2pChannel, deadline)

            val plan = LegacyP2pChannelPlan.plan(band, station, fiveGhz != false)
            diagnostic("Wi-Fi P2P legacy plan channels=${plan.joinToString(",") { if (it == 0) "auto" else "$it" }}")
            var lastFailure: IOException? = null
            for (operatingChannel in plan) {
                ensureOpen()
                if (remaining(deadline) == 0L) break
                val steered = setOperatingChannel(p2pChannel, operatingChannel)
                if (operatingChannel != 0 && steered != true) {
                    // A channel we cannot steer gives no control over the band; try the next one.
                    lastFailure = IOException("Wi-Fi Direct channel $operatingChannel was not accepted")
                    continue
                }
                channelsSteered = channelsSteered || steered == true
                try {
                    createGroup(p2pChannel, deadline)
                } catch (failure: IOException) {
                    lastFailure = failure
                    continue
                }
                // One channel must not use up the whole start-up time if its group never forms.
                val attemptDeadline = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ATTEMPT_MILLIS))
                val info = awaitGroup(p2pChannel, operatingChannel, attemptDeadline)
                if (info != null) return info
                lastFailure = IOException("Wi-Fi Direct group did not become usable on channel ${operatingChannel.takeIf { it != 0 } ?: "auto"}")
                removeGroupBlocking(p2pChannel)
            }
            throw lastFailure ?: IOException("Timed out starting Wi-Fi Direct")
        } catch (failure: Exception) {
            diagnostic("Wi-Fi P2P legacy startup stopped type=${failure.javaClass.simpleName} message=${failure.message}")
            close()
            throw failure
        }
    }

    override fun close() {
        val activeChannel: WifiP2pManager.Channel?
        val activeThread: HandlerThread?
        synchronized(lock) {
            if (closed) return
            closed = true
            activeChannel = channel
            activeThread = thread
            channel = null
            thread = null
        }
        if (activeChannel != null) {
            removeGroupBlocking(activeChannel)
            groupNetworkId?.let { deletePersistentGroup(activeChannel, it) }
            // Give the stock CarPlay and Wi-Fi apps their normal channel choice back.
            if (channelsSteered) setOperatingChannel(activeChannel, 0)
        }
        activeThread?.quitSafely()
    }

    private fun reclaimExistingGroup(p2pChannel: WifiP2pManager.Channel, deadline: Long) {
        val existing = requestGroupInfo(p2pChannel, POLL_NANOS) ?: run {
            diagnostic("Wi-Fi P2P legacy existingGroup=false")
            return
        }
        val recorded = ownership.getString(OWNED_KEY, null)
        val ours = existing.isGroupOwner && !existing.networkName.isNullOrBlank() &&
            existing.networkName == recorded
        diagnostic("Wi-Fi P2P legacy existingGroup=true owner=${existing.isGroupOwner} ours=$ours")
        if (!ours) throw P2pResetRequiredException()
        groupName = existing.networkName
        removeGroupBlocking(p2pChannel)
        while (requestGroupInfo(p2pChannel, POLL_NANOS) != null) {
            if (remaining(deadline) == 0L) throw IOException("Wi-Fi Direct reset did not finish")
            Thread.sleep(100)
        }
        groupName = null
    }

    private fun createGroup(p2pChannel: WifiP2pManager.Channel, deadline: Long) {
        val result = AtomicReference<Int?>()
        val latch = CountDownLatch(1)
        p2pManager.createGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { result.set(SUCCESS); latch.countDown() }
            override fun onFailure(reason: Int) { result.set(reason); latch.countDown() }
        })
        if (!latch.await(remaining(deadline).coerceAtLeast(1L), TimeUnit.NANOSECONDS)) {
            throw IOException("Wi-Fi Direct did not answer the group request")
        }
        val code = result.get()
        if (code != SUCCESS) {
            diagnostic("Wi-Fi P2P legacy create rejected code=$code")
            throw IOException("Wi-Fi Direct group request was rejected (code=$code)")
        }
        diagnostic("Wi-Fi P2P legacy create accepted")
    }

    /** Returns null when the group never formed or came up on a band the plan did not ask for. */
    private fun awaitGroup(
        p2pChannel: WifiP2pManager.Channel,
        requestedChannel: Int,
        deadline: Long,
    ): WirelessHotspotInfo? {
        while (remaining(deadline) > 0) {
            ensureOpen()
            val group = requestGroupInfo(p2pChannel, POLL_NANOS)
            if (group == null) { Thread.sleep(200); continue }
            if (!group.isGroupOwner) throw IOException("Wi-Fi Direct joined another group instead of hosting one")
            val name = group.networkName?.takeIf { it.isNotBlank() }
            val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
            val interfaceName = group.getInterface()?.takeIf { it.isNotBlank() }
            if (name == null || passphrase == null || interfaceName == null) { Thread.sleep(200); continue }
            groupName = name
            groupNetworkId = hiddenNetworkId(group)
            if (!ownership.edit().putString(OWNED_KEY, name).commit()) {
                throw IOException("Could not record Wi-Fi Direct group ownership")
            }

            val frequency = readFrequency(interfaceName, requestedChannel, deadline)
            val channelNumber = frequency?.let(::wifiFrequencyMhzToChannel) ?: 0
            val bandLabel = when {
                frequency == null -> "unknown"
                frequency in 5150..5895 -> "5 GHz"
                frequency in 2412..2484 -> "2.4 GHz"
                else -> "unknown"
            }
            diagnostic("Wi-Fi P2P legacy group interface=$interfaceName requestedChannel=${requestedChannel.takeIf { it != 0 } ?: "auto"} " +
                "actualMHz=${frequency ?: "unknown"} channel=$channelNumber band=$bandLabel")
            if (band == ManualHotspotBand.GHZ_2_4 && bandLabel == "5 GHz") {
                diagnostic("Wi-Fi P2P legacy rejected group: 2.4 GHz requested but group is on 5 GHz")
                return null
            }

            val host = awaitInterfaceAddress(interfaceName, deadline)
            if (host == null) {
                diagnostic("Wi-Fi P2P legacy interface $interfaceName has no address yet")
                Thread.sleep(200)
                continue
            }
            diagnostic("Wi-Fi P2P ready mode=LEGACY band=$bandLabel channel=$channelNumber frequencyMHz=${frequency ?: "unknown"}")
            return WirelessHotspotInfo(
                ssid = name,
                passphrase = passphrase,
                security = Iap2WirelessSecurity.WPA_WPA2,
                channel = channelNumber,
                frequencyMHz = frequency,
                bssid = interfaceHardwareAddress(interfaceName)
                    ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                interfaceName = interfaceName,
                hostAddress = host,
                bandLabel = bandLabel,
                backend = WirelessHotspotBackend.WIFI_P2P,
            )
        }
        return null
    }

    /** Reads the live channel from the driver; a few tries because the GO may still be starting. */
    private fun readFrequency(interfaceName: String, requestedChannel: Int, deadline: Long): Int? {
        val bandHint = when {
            requestedChannel in 1..14 -> "2.4 GHz"
            requestedChannel >= 32 -> "5 GHz"
            else -> null
        }
        val readDeadline = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
        var lastError: String? = null
        while (true) {
            val reading = LegacyHotspotRadio.read(interfaceName, bandHint)
            if (reading.frequencyMHz != null) return reading.frequencyMHz
            lastError = reading.error
            if (remaining(readDeadline) == 0L) break
            Thread.sleep(150)
        }
        diagnostic("Wi-Fi P2P legacy frequency unavailable reason=${lastError ?: "unknown"}")
        return null
    }

    private fun requestGroupInfo(p2pChannel: WifiP2pManager.Channel, timeoutNanos: Long): WifiP2pGroup? {
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        p2pManager.requestGroupInfo(p2pChannel) { result.set(it); latch.countDown() }
        if (!latch.await(timeoutNanos, TimeUnit.NANOSECONDS)) throw IOException("Wi-Fi Direct did not respond")
        return result.get()
    }

    private fun removeGroupBlocking(p2pChannel: WifiP2pManager.Channel) {
        val expected = groupName ?: return
        val latch = CountDownLatch(1)
        try {
            p2pManager.requestGroupInfo(p2pChannel) { current ->
                if (current == null || !current.isGroupOwner || current.networkName != expected) {
                    if (current != null) diagnostic("Wi-Fi P2P legacy cleanup skipped=another_group")
                    latch.countDown()
                    return@requestGroupInfo
                }
                p2pManager.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = latch.countDown()
                    override fun onFailure(reason: Int) {
                        diagnostic("Wi-Fi P2P legacy remove rejected code=$reason")
                        latch.countDown()
                    }
                })
            }
            latch.await(REMOVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: RuntimeException) {
            diagnostic("Wi-Fi P2P legacy remove failed type=${failure.javaClass.simpleName}")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Calls the hidden `setWifiP2pChannels(Channel, listen, operating, listener)`. Operating
     * channel 0 clears the restriction. Returns null when the call is missing on this firmware.
     */
    private fun setOperatingChannel(p2pChannel: WifiP2pManager.Channel, operatingChannel: Int): Boolean? {
        val method = runCatching {
            WifiP2pManager::class.java.getMethod(
                "setWifiP2pChannels",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
        }.getOrElse {
            diagnostic("Wi-Fi P2P legacy setChannels unavailable type=${it.javaClass.simpleName}")
            return null
        }
        val result = AtomicReference<Int?>()
        val latch = CountDownLatch(1)
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() { result.set(SUCCESS); latch.countDown() }
            override fun onFailure(reason: Int) { result.set(reason); latch.countDown() }
        }
        return try {
            method.invoke(p2pManager, p2pChannel, 0, operatingChannel, listener)
            val answered = latch.await(REMOVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            val code = result.get()
            diagnostic("Wi-Fi P2P legacy setChannels operating=$operatingChannel answered=$answered code=${code ?: "none"}")
            answered && code == SUCCESS
        } catch (failure: Exception) {
            diagnostic("Wi-Fi P2P legacy setChannels failed type=${(failure.cause ?: failure).javaClass.simpleName}")
            false
        }
    }

    /** Android 9's two-argument createGroup saves a persistent group; remove it so each session starts clean. */
    private fun deletePersistentGroup(p2pChannel: WifiP2pManager.Channel, networkId: Int) {
        if (networkId < 0) return
        runCatching {
            val method = WifiP2pManager::class.java.getMethod(
                "deletePersistentGroup",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
            val latch = CountDownLatch(1)
            method.invoke(p2pManager, p2pChannel, networkId, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = latch.countDown()
                override fun onFailure(reason: Int) = latch.countDown()
            })
            latch.await(REMOVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }.onFailure { diagnostic("Wi-Fi P2P legacy deletePersistentGroup failed type=${it.javaClass.simpleName}") }
    }

    private fun hiddenNetworkId(group: WifiP2pGroup): Int? = runCatching {
        WifiP2pGroup::class.java.getMethod("getNetworkId").invoke(group) as Int
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun stationFrequency(wifi: WifiManager?): Int? = runCatching {
        val info = wifi?.connectionInfo ?: return@runCatching null
        info.frequency.takeIf { info.supplicantState == SupplicantState.COMPLETED && it > 0 }
    }.getOrNull()

    private fun awaitInterfaceAddress(interfaceName: String, deadline: Long): InetAddress? {
        // IPv6 link-local can appear shortly after the group; prefer it, then fall back to IPv4.
        val addressDeadline = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
        while (true) {
            ensureOpen()
            val address = interfaceAddress(interfaceName)
            if (address is Inet6Address || remaining(addressDeadline) == 0L) return address
            Thread.sleep(100)
        }
    }

    private fun interfaceAddress(interfaceName: String): InetAddress? {
        val networkInterface = networkInterface(interfaceName) ?: return null
        var ipv4: InetAddress? = null
        for (address in Collections.list(networkInterface.inetAddresses)) {
            if (address is Inet6Address && address.isLinkLocalAddress) {
                if (address.scopeId == networkInterface.index) return address
                try {
                    return Inet6Address.getByAddress(null, address.address, networkInterface)
                } catch (_: UnknownHostException) {
                    continue
                }
            }
            if (address is Inet4Address && !address.isLoopbackAddress && ipv4 == null) ipv4 = address
        }
        return ipv4
    }

    private fun interfaceHardwareAddress(interfaceName: String): String? =
        networkInterface(interfaceName)?.hardwareAddress?.takeIf { it.isNotEmpty() }
            ?.joinToString(":") { "%02x".format(it.toInt() and 0xff) }

    private fun networkInterface(interfaceName: String): NetworkInterface? = try {
        NetworkInterface.getByName(interfaceName)
    } catch (_: SocketException) {
        null
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Wi-Fi Direct was closed while starting")
    }

    private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(0L)

    private companion object {
        const val SUCCESS = Int.MIN_VALUE
        const val OWNED_KEY = "owned_ssid"
        const val REMOVE_TIMEOUT_MILLIS = 2_000L
        const val ATTEMPT_MILLIS = 7_000L
        val POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)
    }
}

/** Operating channels to try in order; 0 lets the framework choose. Pure, so it is unit tested. */
internal object LegacyP2pChannelPlan {
    private val fiveGhz = listOf(36, 149)
    private val twoGhz = listOf(6, 1, 11)

    fun plan(band: ManualHotspotBand, stationFrequencyMHz: Int?, fiveGhzSupported: Boolean): List<Int> {
        val station = stationFrequencyMHz?.let(::wifiFrequencyMhzToChannel)
        val stationIs5 = station != null && station >= 32
        val channels = linkedSetOf<Int>()
        // A head unit already joined to a network shares one radio, so its channel goes first.
        if (station != null && (band != ManualHotspotBand.GHZ_2_4 || !stationIs5) &&
            (band != ManualHotspotBand.GHZ_5 || stationIs5)) channels += station
        when (band) {
            ManualHotspotBand.GHZ_2_4 -> channels += twoGhz
            ManualHotspotBand.GHZ_5 -> if (fiveGhzSupported) channels += fiveGhz
            ManualHotspotBand.AUTO -> {
                if (fiveGhzSupported) channels += fiveGhz
                channels += twoGhz
            }
        }
        // Firmware without the hidden channel call still gets one framework-chosen attempt.
        if (band != ManualHotspotBand.GHZ_5) channels += 0
        return channels.toList()
    }
}
