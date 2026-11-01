package com.shilapi.xcertplay.airplay

import java.io.File

/**
 * Head-unit network counters for the diagnostic log, to tell Wi-Fi loss in the air from loss
 * inside the car. UDP `RcvbufErrors` rises when the car's own receive queue overflows (a busy
 * head unit); `ap0` drops and errors are the hotspot driver's. If neither moves while audio
 * packets go missing, they were lost between the iPhone and the car.
 *
 * Android 9 lets apps read `/proc/net/snmp` and `/proc/net/dev`; missing files are reported as
 * unavailable rather than failing.
 */
internal object NetCounters {
    private const val INTERFACE = "ap0"
    private var last: Map<String, Long>? = null

    /** Changes since the previous call, e.g. `udpInErrors=+0 udpRcvbufErrors=+0 ap0RxDrop=+3`. */
    @Synchronized
    fun delta(): String {
        val now = read()
        if (now.isEmpty()) return "net counters unavailable"
        val previous = last
        last = now
        return now.entries.joinToString(" ") { (name, value) ->
            val before = previous?.get(name)
            if (before == null) "$name=$value" else "$name=+${value - before}"
        }
    }

    private fun read(): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        runCatching {
            val udp = File("/proc/net/snmp").readLines().filter { it.startsWith("Udp:") }
            if (udp.size >= 2) {
                val names = udp[0].split(Regex("\\s+")).drop(1)
                val values = udp[1].split(Regex("\\s+")).drop(1)
                names.zip(values).forEach { (name, value) ->
                    if (name == "InErrors" || name == "RcvbufErrors") {
                        value.toLongOrNull()?.let { result["udp$name"] = it }
                    }
                }
            }
        }
        runCatching {
            File("/proc/net/dev").readLines().firstOrNull { it.trim().startsWith("$INTERFACE:") }?.let { line ->
                val fields = line.substringAfter(':').trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
                // rx: bytes packets errs drop fifo frame compressed multicast; tx: bytes packets errs drop ...
                if (fields.size >= 12) {
                    result["ap0RxPackets"] = fields[1]
                    result["ap0RxErr"] = fields[2]
                    result["ap0RxDrop"] = fields[3]
                    result["ap0TxErr"] = fields[10]
                    result["ap0TxDrop"] = fields[11]
                }
            }
        }
        return result
    }
}
