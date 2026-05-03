package com.tankbriga.engine.network

import kotlinx.serialization.Serializable

/** All packet types. */
enum class PktType(val id: Byte) {
    ROOM_ANNOUNCE(0x01), JOIN_REQ(0x02), JOIN_ACK(0x03), PLAYER_JOINED(0x04),
    LOBBY_SNAPSHOT(0x05), LOBBY_ACK(0x06),
    GAME_START(0x10), TURN_START(0x11),
    ACTION(0x12), ACTION_FWRD(0x13), TURN_TIMEOUT(0x14),
    SHOT_RESOLVE(0x15),
    AIM_STATE(0x16),
    TURN_ACK(0x17), ACTION_ACK(0x18), RESOLVE_ACK(0x19),
    ELIM(0x20), GAME_OVER(0x21),
    HEARTBEAT(0x30), COORD_ELECTED(0x31),
    RESYNC_REQ(0x40), RESYNC_ACK(0x41),
    // --- Reconnection ---
    REJOIN_REQ(0x50),   // Player: "voltei, tenho a palavra X"
    REJOIN_ACK(0x51),   // Coordinator: "ok, aqui está o snapshot"
    REJOIN_DENIED(0x52) // Palavra errada ou partida encerrada
}

@Serializable
data class RoomAnnounce(
    val lobbyWord: String,
    val coordinatorIp: String,
    val gamePort: Int = 45679,
    val playerCount: Int,
    val countdown: Int,     // segundos restantes; -1 = em jogo (aceita rejoin)
    val roomId: Int = 0
)

@Serializable
data class PlayerSlot(
    val id: Byte,
    val name: String,
    val color: Int,
    val hp: Int,
    val x: Float,
    val y: Float
)

@Serializable
data class LobbySnapshot(
    val lobbyWord: String,
    val hostId: Byte,
    val players: List<PlayerSlot>
)

/** Payload de REJOIN_REQ — enviado via unicast ao coordinator. */
@Serializable
data class RejoinRequest(
    val lobbyWord: String,
    val playerId: Byte,
    val playerName: String
)

/**
 * Snapshot mínimo enviado ao player que voltou.
 * Não recria o terreno todo — reutiliza o bitmap do DeterministicRng,
 * só transmite os deltas (lista de cráteras) + HP + posições.
 */
@Serializable
data class RejoinSnapshot(
    val turnNumber: Short,
    val currentTurnPlayerId: Byte,
    val tankHps: List<Int>,
    val tankPositionsX: List<Float>,
    val tankPositionsY: List<Float>,
    val craterCentersX: List<Int>,
    val craterCentersY: List<Int>,
    val craterRadii: List<Int>
)

/** Turno anunciado pelo coordinator a todos. */
@Serializable
data class TurnStartPacket(
    val playerId: Byte,
    val windValue: Float,
    val turnNumber: Short,
    val timerMs: Int = 15_000,
    val serverStartMs: Long = 0L
)

@Serializable
data class ShotResolvePacket(
    val shotId: Short,
    val turnNumber: Short,
    val shooterId: Byte,
    val resultType: Byte,
    val impactX: Float,
    val impactY: Float,
    val shotType: Byte,
    val shotAngle: Float,
    val directTankId: Byte?,
    val craterRadius: Int,
    val tankIds: List<Byte>,
    val tankHps: List<Int>,
    val tankPositionsX: List<Float>,
    val tankPositionsY: List<Float>,
    val eliminated: List<Byte>
)

@Serializable
data class AimStatePacket(
    val playerId: Byte,
    val angle: Float,
    val power: Float,
    val charging: Boolean,
    val turnNumber: Short
)

@Serializable
data class AckPacket(
    val kind: Byte,
    val turnNumber: Short,
    val shotId: Short = 0
)

/** Eleição de coordinator. */
@Serializable
data class CoordElectedPacket(
    val newCoordId: Byte,
    val epoch: Short
)

// ── Binary serialization para AimStatePacket (7 bytes) ──────────────────────

fun AimStatePacket.toBinary(): ByteArray {
    val buf = ByteArray(7)
    buf[0] = playerId
    val a = (angle * 10).toInt().toShort()
    buf[1] = (a.toInt() shr 8).toByte()
    buf[2] = (a.toInt() and 0xFF).toByte()
    buf[3] = power.toInt().toByte()
    buf[4] = if (charging) 1.toByte() else 0.toByte()
    buf[5] = (turnNumber.toInt() shr 8).toByte()
    buf[6] = (turnNumber.toInt() and 0xFF).toByte()
    return buf
}

fun ByteArray.toAimStatePacket(): AimStatePacket? {
    if (this.size < 7) return null
    val angle = (((this[1].toInt() and 0xFF) shl 8) or (this[2].toInt() and 0xFF)).toShort() / 10f
    val power = (this[3].toInt() and 0xFF).toFloat()
    val charging = this[4].toInt() == 1
    val turnNumber = (((this[5].toInt() and 0xFF) shl 8) or (this[6].toInt() and 0xFF)).toShort()
    return AimStatePacket(this[0], angle, power, charging, turnNumber)
}

// ── Binary serialization para ActionPacket (8 bytes, zero alloc) ─────────────

fun com.tankbriga.engine.ActionPacket.toBinary(): ByteArray {
    val buf = ByteArray(8)
    buf[0] = playerId
    buf[1] = (angleTenths.toInt() shr 8).toByte()
    buf[2] = (angleTenths.toInt() and 0xFF).toByte()
    buf[3] = power
    buf[4] = shotType
    buf[5] = moveDir
    buf[6] = (seq.toInt() shr 8).toByte()
    buf[7] = (seq.toInt() and 0xFF).toByte()
    return buf
}

fun ByteArray.toActionPacket(): com.tankbriga.engine.ActionPacket? {
    if (this.size < 8) return null
    return com.tankbriga.engine.ActionPacket(
        playerId  = this[0],
        angleTenths = (((this[1].toInt() and 0xFF) shl 8) or (this[2].toInt() and 0xFF)).toShort(),
        power     = this[3],
        shotType  = this[4],
        moveDir   = this[5],
        seq       = (((this[6].toInt() and 0xFF) shl 8) or (this[7].toInt() and 0xFF)).toShort()
    )
}

// ── SecureEnvelope (11-byte header + payload) ─────────────────────────────────

class SecureEnvelope(
    val playerId: Byte,
    val seq: Short,
    val signature: Long,
    val payload: ByteArray
) {
    fun toBinary(): ByteArray {
        val out = ByteArray(11 + payload.size)
        out[0] = playerId
        out[1] = (seq.toInt() shr 8).toByte()
        out[2] = (seq.toInt() and 0xFF).toByte()
        for (i in 0 until 8) out[3 + i] = ((signature shr (56 - i * 8)) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 11, payload.size)
        return out
    }

    companion object {
        fun fromBinary(data: ByteArray): SecureEnvelope? {
            if (data.size < 12) return null // 11 header + mínimo 1 byte payload
            val pid = data[0]
            val seq = (((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)).toShort()
            var sig = 0L
            for (i in 0 until 8) sig = (sig shl 8) or (data[3 + i].toLong() and 0xFF)
            val payload = data.copyOfRange(11, data.size)
            return SecureEnvelope(pid, seq, sig, payload)
        }
    }
}
