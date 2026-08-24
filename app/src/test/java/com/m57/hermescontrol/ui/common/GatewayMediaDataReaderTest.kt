package com.m57.hermescontrol.ui.common

import com.m57.hermescontrol.data.remote.GatewayMediaRangeResult
import com.m57.hermescontrol.data.remote.SeekableGatewayMediaReader
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GatewayMediaDataReaderTest {
    @Test
    fun `read maps position and bounded count then copies at caller offset`() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val session =
            object : SeekableGatewayMediaReader {
                override suspend fun read(
                    position: Long,
                    byteCount: Int,
                ): GatewayMediaRangeResult {
                    calls += position to byteCount
                    return GatewayMediaRangeResult.Success(byteArrayOf(1, 2, 3), 99L, "audio/mpeg")
                }
            }
        val reader = GatewayMediaDataReader(session)
        val target = ByteArray(8)

        assertEquals(3, reader.readAt(12L, target, 2, 5))
        assertEquals(listOf(12L to 5), calls)
        assertArrayEquals(byteArrayOf(0, 0, 1, 2, 3, 0, 0, 0), target)
        assertEquals(99L, reader.size())
    }

    @Test
    fun `read is capped to transport maximum and end of file returns minus one`() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val session =
            object : SeekableGatewayMediaReader {
                override suspend fun read(
                    position: Long,
                    byteCount: Int,
                ): GatewayMediaRangeResult {
                    calls += position to byteCount
                    return GatewayMediaRangeResult.Success(ByteArray(byteCount), 10L, null)
                }
            }
        val reader = GatewayMediaDataReader(session)

        val oversizedCount = SeekableGatewayMediaReader.MAX_RANGE_BYTES + 4
        reader.readAt(0, ByteArray(oversizedCount), 0, oversizedCount)
        assertEquals(-1, reader.readAt(10, ByteArray(1), 0, 1))
        assertEquals(SeekableGatewayMediaReader.MAX_RANGE_BYTES, calls.single().second)
    }

    @Test
    fun `stale and transport failures surface as io errors`() {
        listOf(
            GatewayMediaRangeResult.Stale,
            GatewayMediaRangeResult.Forbidden,
            GatewayMediaRangeResult.Failure(IllegalStateException("boom")),
        ).forEach { result ->
            val reader = GatewayMediaDataReader(SeekableGatewayMediaReader { _, _ -> result })

            assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(1), 0, 1) }
        }
    }

    @Test
    fun `close is idempotent and terminates subsequent reads`() {
        var calls = 0
        val reader =
            GatewayMediaDataReader(
                SeekableGatewayMediaReader { _, _ ->
                    calls++
                    GatewayMediaRangeResult.Success(byteArrayOf(1), 1L, null)
                },
            )

        reader.close()
        reader.close()

        assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(1), 0, 1) }
        assertEquals(0, calls)
    }

    @Test
    fun `overlapping reads both complete`() {
        val bothStarted = CountDownLatch(2)
        val release = CountDownLatch(1)
        val reader =
            GatewayMediaDataReader(
                SeekableGatewayMediaReader { position, _ ->
                    bothStarted.countDown()
                    assertTrue(bothStarted.await(1, TimeUnit.SECONDS))
                    release.await(1, TimeUnit.SECONDS)
                    GatewayMediaRangeResult.Success(byteArrayOf(position.toByte()), 2L, null)
                },
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Int> { reader.readAt(0, ByteArray(1), 0, 1) }
            val second = executor.submit<Int> { reader.readAt(1, ByteArray(1), 0, 1) }

            assertTrue(bothStarted.await(1, TimeUnit.SECONDS))
            release.countDown()

            assertEquals(1, first.get(1, TimeUnit.SECONDS))
            assertEquals(1, second.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `close promptly cancels all overlapping suspended reads without blocking caller`() {
        val started = CountDownLatch(2)
        val reader =
            GatewayMediaDataReader(
                SeekableGatewayMediaReader { _, _ ->
                    started.countDown()
                    awaitCancellation()
                },
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Int> { reader.readAt(0, ByteArray(1), 0, 1) }
            val second = executor.submit<Int> { reader.readAt(1, ByteArray(1), 0, 1) }
            assertTrue(started.await(1, TimeUnit.SECONDS))

            val before = System.nanoTime()
            reader.close()
            val closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)

            assertTrue("close blocked for $closeMillis ms", closeMillis < 100)
            assertThrows(Exception::class.java) { first.get(1, TimeUnit.SECONDS) }
            assertThrows(Exception::class.java) { second.get(1, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
