package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReportDownloadServerTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun tokenIs128RandomBitsAsHex() {
        val first = ReportDownloadHttp.newToken(SecureRandom())
        val second = ReportDownloadHttp.newToken(SecureRandom())
        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(first, second)
    }

    @Test fun onlyTheExactTokenPathIsServed() {
        fun route(method: String, target: String) =
            ReportDownloadHttp.route(ReportDownloadHttp.Request(method, target), token)
        assertEquals(ReportDownloadHttp.Route.SERVE, route("GET", "/$token"))
        assertEquals(ReportDownloadHttp.Route.SERVE, route("GET", "/$token?x=1"))
        assertEquals(ReportDownloadHttp.Route.HEAD, route("HEAD", "/$token"))
        assertEquals(ReportDownloadHttp.Route.METHOD_NOT_ALLOWED, route("POST", "/$token"))
        assertEquals(ReportDownloadHttp.Route.NOT_FOUND, route("GET", "/"))
        assertEquals(ReportDownloadHttp.Route.NOT_FOUND, route("GET", "/favicon.ico"))
        assertEquals(ReportDownloadHttp.Route.NOT_FOUND, route("GET", "/${token.dropLast(1)}"))
        assertEquals(ReportDownloadHttp.Route.NOT_FOUND, route("GET", "/${token}0"))
        assertEquals(ReportDownloadHttp.Route.NOT_FOUND, route("GET", "/$token/"))
        assertEquals(ReportDownloadHttp.Route.NOT_FOUND, route("GET", "/${token.uppercase()}"))
        assertEquals(ReportDownloadHttp.Route.NOT_FOUND, route("POST", "/wrong"))
        assertEquals(ReportDownloadHttp.Route.NOT_FOUND,
            ReportDownloadHttp.route(ReportDownloadHttp.Request("GET", "/"), ""))
    }

    @Test fun parsesRequestLines() {
        assertEquals(ReportDownloadHttp.Request("GET", "/abc"),
            ReportDownloadHttp.parse("GET /abc HTTP/1.1\r\nHost: 192.168.43.1\r\n\r\n"))
        assertEquals(ReportDownloadHttp.Request("HEAD", "/abc"), ReportDownloadHttp.parse("HEAD /abc HTTP/1.0\n\n"))
        assertNull(ReportDownloadHttp.parse("GET /abc\r\n\r\n"))
        assertNull(ReportDownloadHttp.parse("GET  /abc HTTP/1.1\r\n\r\n"))
        assertNull(ReportDownloadHttp.parse("GET http://evil/abc HTTP/1.1\r\n\r\n"))
        assertNull(ReportDownloadHttp.parse("get /abc HTTP/1.1\r\n\r\n"))
        assertNull(ReportDownloadHttp.parse("GET /abc HTTP/2\r\n\r\n"))
        assertNull(ReportDownloadHttp.parse(""))
    }

    @Test fun readsHeadsUpToTheBlankLineOnly() {
        fun read(text: String) = ReportDownloadHttp.readHead(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)))
        assertEquals("GET / HTTP/1.1\r\nA: b\r\n\r\n", read("GET / HTTP/1.1\r\nA: b\r\n\r\nbody"))
        assertEquals("GET / HTTP/1.1\n\n", read("GET / HTTP/1.1\n\n"))
        assertNull(read("GET / HTTP/1.1\r\nA: b\r\n"))
        assertNull(read("GET / HTTP/1.1\r\nX: " + "a".repeat(ReportDownloadHttp.MAX_HEAD_BYTES) + "\r\n\r\n"))
    }

    @Test fun servesOnceThenStops() {
        val body = "report line\n".repeat(1000).toByteArray()
        val logs = mutableListOf<String>()
        val done = CountDownLatch(1)
        var outcome: ReportDownloadServer.Result? = null
        val server = ReportDownloadServer(loopback(), "MG4CPlay-20261102-120000-000.txt", body,
            log = { synchronized(logs) { logs += it } },
            onFinished = { outcome = it; done.countDown() })
        server.start()
        val port = server.url.substringAfterLast(':').substringBefore('/').toInt()
        val path = "/" + server.url.substringAfterLast('/')

        assertTrue(request(port, "GET /nope HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404 "))
        val response = request(port, "GET $path HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("Content-Disposition: attachment; filename=MG4CPlay-20261102-120000-000.txt\r\n"))
        assertTrue(response.contains("Content-Length: ${body.size}\r\n"))
        assertTrue(response.endsWith(String(body)))

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(ReportDownloadServer.Result.SERVED, outcome)
        assertTrue(synchronized(logs) { logs.single() }.matches(Regex("Report download served bytes=${body.size} ms=\\d+")))
        assertTrue(runCatching { request(port, "GET $path HTTP/1.1\r\n\r\n") }.let { it.isFailure || it.getOrThrow().isEmpty() })
    }

    @Test fun expiresAfterItsLifetime() {
        val logs = mutableListOf<String>()
        val done = CountDownLatch(1)
        var outcome: ReportDownloadServer.Result? = null
        val server = ReportDownloadServer(loopback(), "r.txt", ByteArray(1),
            log = { synchronized(logs) { logs += it } },
            onFinished = { outcome = it; done.countDown() }, lifetimeMillis = 50)
        server.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(ReportDownloadServer.Result.EXPIRED, outcome)
        assertEquals(listOf("Report download expired"), synchronized(logs) { logs.toList() })
        server.close()
        assertEquals(1, synchronized(logs) { logs.size })
    }

    @Test fun closeStopsAndIsReportedOnce() {
        val logs = mutableListOf<String>()
        var outcomes = 0
        val server = ReportDownloadServer(loopback(), "r.txt", ByteArray(1),
            log = { synchronized(logs) { logs += it } }, onFinished = { outcomes++ })
        server.start()
        server.close(); server.close()
        assertEquals(1, outcomes)
        assertEquals(listOf("Report download stopped"), logs)
    }

    @Test fun neverBindsToAWildcardOrLoopbackAddress() {
        assertNull(ReportDownloadServer.bindAddress(InetAddress.getByName("0.0.0.0"), null))
        assertNull(ReportDownloadServer.bindAddress(InetAddress.getByName("127.0.0.1"), null))
        assertNull(ReportDownloadServer.bindAddress(InetAddress.getByName("fe80::1"), null))
        assertNull(ReportDownloadServer.bindAddress(null, "no-such-interface0"))
        assertEquals(InetAddress.getByName("192.168.43.1"),
            ReportDownloadServer.bindAddress(InetAddress.getByName("192.168.43.1"), null))
    }

    private fun loopback() = InetAddress.getByName("127.0.0.1") as Inet4Address

    private fun request(port: Int, text: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().apply { write(text.toByteArray()); flush() }
        socket.shutdownOutput()
        String(socket.getInputStream().readBytes(), Charsets.ISO_8859_1)
    }
}
