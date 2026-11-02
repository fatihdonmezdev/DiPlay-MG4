package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class QrCodeTest {
    @Test fun picksTheSmallestVersionThatFits() {
        assertEquals(21, QrCode.encode("a".repeat(14)).size)   // version 1-M holds 14 bytes
        assertEquals(25, QrCode.encode("a".repeat(15)).size)
        // A report URL: http://192.168.255.255:65535/ + 32 hex characters.
        assertEquals(33, QrCode.encode("http://192.168.255.255:65535/" + "f".repeat(32)).size)
        assertEquals(57, QrCode.encode("a".repeat(213)).size)  // version 10-M upper limit
        assertThrows(IllegalArgumentException::class.java) { QrCode.encode("a".repeat(214)) }
    }

    @Test fun drawsFinderPatternsAndTiming() {
        val code = QrCode.encode("http://192.168.43.1:40000/0123456789abcdef0123456789abcdef")
        val n = code.size
        for ((cx, cy) in listOf(3 to 3, n - 4 to 3, 3 to n - 4)) {
            for (d in -3..3) {
                assertTrue(code.isDark(cx + d, cy - 3)); assertTrue(code.isDark(cx + d, cy + 3))
                assertTrue(code.isDark(cx - 3, cy + d)); assertTrue(code.isDark(cx + 3, cy + d))
            }
            assertFalse(code.isDark(cx - 2, cy)); assertTrue(code.isDark(cx, cy))
        }
        for (i in 8 until n - 8) assertEquals(i % 2 == 0, code.isDark(i, 6))
        assertTrue(code.isDark(8, n - 8))
    }

    @Test fun formatInformationIsLevelMAndBothCopiesAgree() {
        val code = QrCode.encode("http://192.168.43.1:40000/0123456789abcdef0123456789abcdef")
        val n = code.size
        var first = 0
        var second = 0
        val firstCells = (0..5).map { 8 to it } + listOf(8 to 7, 8 to 8, 7 to 8) + (9 until 15).map { 14 - it to 8 }
        val secondCells = (0 until 8).map { n - 1 - it to 8 } + (8 until 15).map { 8 to n - 15 + it }
        firstCells.forEachIndexed { i, (x, y) -> if (code.isDark(x, y)) first = first or (1 shl i) }
        secondCells.forEachIndexed { i, (x, y) -> if (code.isDark(x, y)) second = second or (1 shl i) }
        assertEquals(first, second)
        val data = (first xor 0x5412) ushr 10
        assertEquals(0, data ushr 3) // error correction level M
    }
}
