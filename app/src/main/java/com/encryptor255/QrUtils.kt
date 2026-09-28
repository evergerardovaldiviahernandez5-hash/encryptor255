package com.encryptor255

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

object QrUtils {

    fun generate(text: String, sizePx: Int = 800): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = MultiFormatWriter().encode(
            text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
        )
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(sizePx * sizePx)
        var idx = 0
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[idx++] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bmp
    }

    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return try {
            MultiFormatReader().decode(binary).text
        } catch (_: Throwable) {
            null
        }
    }
}
