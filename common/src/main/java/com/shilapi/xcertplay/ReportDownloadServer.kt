package com.shilapi.xcertplay

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Request parsing and routing for [ReportDownloadServer], kept free of sockets for tests. */
internal object ReportDownloadHttp {
    const val MAX_HEAD_BYTES = 8 * 1024
    const val TOKEN_BYTES = 16

    data class Request(val method: String, val target: String)
    enum class Route { SERVE, HEAD, NOT_FOUND, METHOD_NOT_ALLOWED }

    /** 128 random bits as lowercase hex: the whole secret in the download URL. */
    fun newToken(random: SecureRandom): String =
        ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Reads up to the blank line that ends an HTTP head; null if it is cut off or too long. */
    fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var last4 = 0
        while (buffer.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A || last4 and 0xFFFF == 0x0A0A) return buffer.toString("ISO-8859-1")
        }
        return null
    }

    /** Parses the request line of an HTTP/1.x head; null for anything malformed. */
    fun parse(head: String): Request? {
        val parts = head.substringBefore('\n').removeSuffix("\r").split(' ')
        if (parts.size != 3) return null
        val (method, target, version) = parts
        if (method.isEmpty() || !method.all { it in 'A'..'Z' }) return null
        if (!target.startsWith("/")) return null
        if (version != "HTTP/1.1" && version != "HTTP/1.0") return null
        return Request(method, target)
    }

    /** Only the exact token path exists; every other path is a 404 whatever the method. */
    fun route(request: Request, token: String): Route {
        val path = request.target.substringBefore('?')
        if (token.isEmpty() || !MessageDigest.isEqual(path.toByteArray(Charsets.US_ASCII), "/$token".toByteArray(Charsets.US_ASCII))) {
            return Route.NOT_FOUND
        }
        return when (request.method) {
            "GET" -> Route.SERVE
            "HEAD" -> Route.HEAD
            else -> Route.METHOD_NOT_ALLOWED
        }
    }

    fun head(status: String, contentLength: Int, vararg extra: String): ByteArray = buildString {
        append("HTTP/1.1 ").append(status).append("\r\n")
        append("Content-Length: ").append(contentLength).append("\r\n")
        append("Cache-Control: no-store\r\n")
        append("X-Content-Type-Options: nosniff\r\n")
        append("Connection: close\r\n")
        extra.forEach { append(it).append("\r\n") }
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)
}

/**
 * Serves one diagnostic report, once, to the iPhone on the car's own CarPlay Wi-Fi. Binds only to
 * that network's IPv4 address, never to all interfaces. The URL path carries a 128-bit one-time
 * token; anything else gets a 404. Stops after one complete download, after [lifetimeMillis], or
 * on [close].
 */
internal class ReportDownloadServer(
    address: Inet4Address,
    private val fileName: String,
    private val body: ByteArray,
    private val log: (String) -> Unit,
    private val onFinished: (Result) -> Unit,
    private val lifetimeMillis: Long = LIFETIME_MILLIS,
    random: SecureRandom = SecureRandom(),
) : Closeable {
    enum class Result { SERVED, EXPIRED, CLOSED, FAILED }

    private val token = ReportDownloadHttp.newToken(random)
    private val socket = ServerSocket(0, 4, address).apply { soTimeout = ACCEPT_POLL_MILLIS }
    private val finished = AtomicBoolean(false)
    private val transferring = AtomicBoolean(false)
    val url = "http://${address.hostAddress}:${socket.localPort}/$token"
    val size get() = body.size

    fun start() {
        Thread(::acceptLoop, "report-download").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifetimeMillis)
        try {
            while (!finished.get()) {
                if (System.nanoTime() >= deadline) {
                    finish(Result.EXPIRED, "Report download expired")
                    break
                }
                val client = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
                // Own thread per connection: Safari may open an idle preconnection first.
                Thread({ client.use(::handle) }, "report-download-client").apply { isDaemon = true }.start()
            }
        } catch (error: IOException) {
            finish(Result.FAILED, "Report download failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = CLIENT_TIMEOUT_MILLIS
            val output = client.getOutputStream()
            val request = ReportDownloadHttp.readHead(client.getInputStream())?.let(ReportDownloadHttp::parse)
            if (request == null) {
                output.write(ReportDownloadHttp.head("400 Bad Request", 0)); output.flush(); return
            }
            when (ReportDownloadHttp.route(request, token)) {
                ReportDownloadHttp.Route.NOT_FOUND -> {
                    output.write(ReportDownloadHttp.head("404 Not Found", 0))
                }
                ReportDownloadHttp.Route.METHOD_NOT_ALLOWED -> {
                    output.write(ReportDownloadHttp.head("405 Method Not Allowed", 0, "Allow: GET, HEAD"))
                }
                ReportDownloadHttp.Route.HEAD -> output.write(reportHead())
                ReportDownloadHttp.Route.SERVE -> {
                    if (finished.get() || !transferring.compareAndSet(false, true)) {
                        output.write(ReportDownloadHttp.head("503 Service Unavailable", 0))
                    } else {
                        serve(client)
                        return
                    }
                }
            }
            output.flush()
        } catch (_: IOException) {
            // The client went away; nothing was served.
        }
    }

    private fun serve(client: Socket) {
        val started = System.nanoTime()
        try {
            val output = client.getOutputStream()
            output.write(reportHead())
            output.write(body)
            output.flush()
            client.shutdownOutput()
            // Wait for the iPhone to close its end, so the time covers delivery, not just buffering.
            try { while (client.getInputStream().read() >= 0) Unit } catch (_: SocketTimeoutException) {}
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            finish(Result.SERVED, "Report download served bytes=${body.size} ms=$ms")
        } catch (error: IOException) {
            transferring.set(false)
            log("Report download interrupted: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun reportHead() = ReportDownloadHttp.head(
        "200 OK", body.size,
        "Content-Type: text/plain; charset=utf-8",
        "Content-Disposition: attachment; filename=$fileName",
    )

    private fun finish(result: Result, message: String) {
        if (!finished.compareAndSet(false, true)) return
        runCatching { socket.close() }
        log(message)
        onFinished(result)
    }

    override fun close() = finish(Result.CLOSED, "Report download stopped")

    companion object {
        const val LIFETIME_MILLIS = 5 * 60 * 1000L
        private const val ACCEPT_POLL_MILLIS = 1000
        private const val CLIENT_TIMEOUT_MILLIS = 15_000

        /**
         * The IPv4 address of the CarPlay Wi-Fi interface (`ap0`, `p2p-*`), or null if it has none.
         * Never a wildcard or loopback address.
         */
        fun bindAddress(hostAddress: InetAddress?, interfaceName: String?): Inet4Address? {
            fun usable(address: InetAddress?) = (address as? Inet4Address)
                ?.takeUnless { it.isAnyLocalAddress || it.isLoopbackAddress }
            if (interfaceName == null) return usable(hostAddress)
            val candidates = try {
                NetworkInterface.getByName(interfaceName)?.inetAddresses?.toList().orEmpty().mapNotNull(::usable)
            } catch (_: Exception) {
                emptyList()
            }
            return candidates.firstOrNull { it == hostAddress } ?: candidates.firstOrNull()
        }
    }
}
