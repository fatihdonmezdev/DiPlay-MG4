package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Accepts until a connection comes from [expected], the control-session peer; others are closed unread.
 * Java reports an IPv4-mapped peer as its IPv4 address and ignores the scope of a link-local one,
 * so a plain address comparison covers the hotspot, Wi-Fi Direct and USB NCM paths (upstream DiPlay #1123).
 */
internal fun ServerSocket.acceptFrom(expected: InetAddress, onRejected: (InetAddress?) -> Unit = {}): Socket {
    while (true) {
        val socket = accept()
        if (socket.inetAddress == expected) return socket
        onRejected(socket.inetAddress)
        runCatching { socket.close() }
    }
}
