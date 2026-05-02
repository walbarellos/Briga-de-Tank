package com.tankbriga.engine.network

import com.tankbriga.engine.ActionPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NetworkProtocolTest {

    @Test
    fun `ActionPacket binary serialization should be bit-perfect and exactly 8 bytes`() {
        val original = ActionPacket(
            playerId = 3,
            angleTenths = 1355, // 135.5 degrees
            power = 85,
            shotType = 2, // Ricochet
            moveDir = -1,
            seq = 42
        )

        val binary = original.toBinary()
        assertEquals(8, binary.size, "Binary payload must be exactly 8 bytes")

        val decoded = binary.toActionPacket()
        assertNotNull(decoded)
        assertEquals(original.playerId, decoded.playerId)
        assertEquals(original.angleTenths, decoded.angleTenths)
        assertEquals(original.power, decoded.power)
        assertEquals(original.shotType, decoded.shotType)
        assertEquals(original.moveDir, decoded.moveDir)
        assertEquals(original.seq, decoded.seq)
    }

    @Test
    fun `Serialization should handle boundary values`() {
        val original = ActionPacket(
            playerId = 7,
            angleTenths = 1800,
            power = 100,
            shotType = 4,
            moveDir = 1,
            seq = Short.MAX_VALUE
        )

        val decoded = original.toBinary().toActionPacket()
        assertEquals(original, decoded)
    }
}
