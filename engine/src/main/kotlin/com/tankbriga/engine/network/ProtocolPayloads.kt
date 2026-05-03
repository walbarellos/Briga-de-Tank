package com.tankbriga.engine.network

import com.tankbriga.engine.ActionPacket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProtocolPayloads {
    inline fun <reified T> json(type: PktType, value: T): ByteArray {
        val json = Json.encodeToString(value).toByteArray()
        return ByteArray(1 + json.size).also {
            it[0] = type.id
            json.copyInto(it, 1)
        }
    }

    fun action(type: PktType, action: ActionPacket): ByteArray {
        require(type == PktType.ACTION || type == PktType.ACTION_FWRD) {
            "Action payload must use ACTION or ACTION_FWRD"
        }
        val bin = action.toBinary()
        return ByteArray(1 + bin.size).also {
            it[0] = type.id
            bin.copyInto(it, 1)
        }
    }

    fun ack(type: PktType, turnNumber: Short, shotId: Short): ByteArray {
        require(type == PktType.LOBBY_ACK || type == PktType.TURN_ACK || type == PktType.ACTION_ACK || type == PktType.RESOLVE_ACK) {
            "Ack payload must use an ACK packet type"
        }
        return json(type, AckPacket(type.id, turnNumber, shotId))
    }

    fun joinRequest(playerName: String): ByteArray {
        val nameBytes = playerName.toByteArray()
        return ByteArray(1 + nameBytes.size).also {
            it[0] = PktType.JOIN_REQ.id
            nameBytes.copyInto(it, 1)
        }
    }

    fun joinAck(assignedId: Byte, hostName: String): ByteArray {
        val nameBytes = hostName.toByteArray()
        return ByteArray(4 + nameBytes.size).also {
            it[0] = PktType.JOIN_ACK.id
            it[1] = 0
            it[2] = 0
            it[3] = assignedId
            nameBytes.copyInto(it, 4)
        }
    }

    fun transportAck(seq: Short): ByteArray {
        return ByteArray(3).also {
            it[0] = PktType.JOIN_ACK.id
            it[1] = (seq.toInt() shr 8).toByte()
            it[2] = (seq.toInt() and 0xFF).toByte()
        }
    }

    fun rejoinRequest(req: RejoinRequest): ByteArray = json(PktType.REJOIN_REQ, req)
}
