package com.tankbriga.app.network

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.InetAddress

/**
 * Utility to generate QR Codes containing Host IP and Room Secret.
 * Allows joining even if Multicast is blocked in institutional networks.
 */
object QrCodeGenerator {

    /**
     * Encodes network data into a QR string: "TB|IP|PORT|SECRET|ROOM_WORD"
     */
    fun generateDataString(ip: String, port: Int, word: String): String {
        return "TB|$ip|$port|$word"
    }

    fun createBitmap(content: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
