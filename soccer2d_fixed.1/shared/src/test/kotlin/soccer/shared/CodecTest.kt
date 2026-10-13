package soccer.shared

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CodecTest {
    @Test fun snapshotRoundTrip() {
        val players = listOf(
            PState(1, 0, 0, 3f, 4f, 0.5f, -0.5f, 77, true),
            PState(51, 1, 1, 20f, 9f, 1f, 2f, 0, false)
        )
        val s = Snapshot(123, 15f, 9f, 1f, -1f, 2, 3, State.PLAYING, 90, players)
        val bytes = Codec.encodeSnap(s)
        assertEquals(1 + 4 + 16 + 3 + 3 + players.size * 24, bytes.size)
        val b = ByteBuffer.wrap(bytes)
        assertEquals(Op.SNAP, b.get().toInt())
        val d = Codec.decodeSnap(b)
        assertEquals(123, d.tick); assertEquals(2, d.scoreA); assertEquals(3, d.scoreB)
        assertEquals(State.PLAYING, d.state); assertEquals(90, d.timer)
        assertEquals(2, d.players.size)
        assertEquals(0, d.players[0].slot); assertEquals(77, d.players[0].ack); assertEquals(true, d.players[0].ready)
        assertEquals(51, d.players[1].id); assertEquals(1, d.players[1].slot); assertEquals(20f, d.players[1].x)
    }

    @Test fun inputRoundTrip() {
        val b = ByteBuffer.wrap(Codec.input(42, 0.5f, -1f, Act.SHOOT, 3))
        assertEquals(Codec.INPUT_LEN, b.capacity())
        assertEquals(Op.INPUT, b.get().toInt())
        assertEquals(Input(42, 0.5f, -1f, Act.SHOOT, 3), Codec.decodeInput(b))
        val a = ByteArray(Codec.INPUT_LEN); Codec.writeInput(a, 42, 0.5f, -1f, Act.SHOOT, 3)
        assertEquals(Codec.input(42, 0.5f, -1f, Act.SHOOT, 3).toList(), a.toList())
    }

    @Test fun joinLayout() {
        val b = ByteBuffer.wrap(Codec.join(-1, 99L, 5))
        assertEquals(Op.JOIN, b.get().toInt())
        assertEquals(-1, b.int); assertEquals(99L, b.long)
        assertEquals(C.PROTOCOL, b.get().toInt()); assertEquals(5, b.get().toInt())
    }

    @Test fun hostSpec() {
        assertEquals(C.PORT, HostSpec.parse("192.168.1.5")!!.port)
        assertEquals(9000, HostSpec.parse("example.com:9000")!!.port)
        assertNull(HostSpec.parse(""))
        assertNull(HostSpec.parse("host:99999"))
        assertNotNull(HostSpec.parse(" 10.0.0.2:7777 "))
    }
}
