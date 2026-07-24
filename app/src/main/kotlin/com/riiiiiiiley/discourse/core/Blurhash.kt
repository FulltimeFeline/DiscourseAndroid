package com.riiiiiiiley.discourse.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.withSign

/** Blurhash (https://blurha.sh); the SDK requires one on outgoing images. */
object Blurhash {
    private val alphabet =
        ("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
            "#$%*+,-.:;=?@[]^_{|}~").toCharArray()

    fun encode(imageData: ByteArray, componentsX: Int = 4, componentsY: Int = 3): String? {
        // Downsample to ≤32px before the O(w·h·cx·cy) transform, like the iOS
        // thumbnailing pass.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 32) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, opts)
            ?: return null
        val largest = max(decoded.width, decoded.height)
        val bitmap = if (largest > 32) {
            val scale = 32.0 / largest
            Bitmap.createScaledBitmap(
                decoded,
                max(1, (decoded.width * scale).toInt()),
                max(1, (decoded.height * scale).toInt()),
                true,
            )
        } else {
            decoded
        }
        return encode(bitmap, componentsX, componentsY)
    }

    fun encode(bitmap: Bitmap, componentsX: Int, componentsY: Int): String? {
        if (componentsX !in 1..9 || componentsY !in 1..9) return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val factors = ArrayList<DoubleArray>(componentsX * componentsY)
        for (j in 0 until componentsY) {
            for (i in 0 until componentsX) {
                val normalisation = if (i == 0 && j == 0) 1.0 else 2.0
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val basis = normalisation *
                            cos(Math.PI * i * x / width) *
                            cos(Math.PI * j * y / height)
                        val pixel = pixels[y * width + x]
                        r += basis * sRGBToLinear((pixel shr 16) and 0xFF)
                        g += basis * sRGBToLinear((pixel shr 8) and 0xFF)
                        b += basis * sRGBToLinear(pixel and 0xFF)
                    }
                }
                val scale = 1.0 / (width * height)
                factors.add(doubleArrayOf(r * scale, g * scale, b * scale))
            }
        }

        val dc = factors[0]
        val ac = factors.drop(1)

        val hash = StringBuilder()
        hash.append(encode83((componentsX - 1) + (componentsY - 1) * 9, length = 1))

        var maximumValue = 1.0
        if (ac.isNotEmpty()) {
            val actualMax = ac.maxOf { max(abs(it[0]), max(abs(it[1]), abs(it[2]))) }
            val quantised = max(0, min(82, (actualMax * 166 - 0.5).toInt()))
            maximumValue = (quantised + 1) / 166.0
            hash.append(encode83(quantised, length = 1))
        } else {
            hash.append(encode83(0, length = 1))
        }

        hash.append(encode83(encodeDC(dc), length = 4))
        for (factor in ac) {
            hash.append(encode83(encodeAC(factor, maximumValue), length = 2))
        }
        return hash.toString()
    }

    // MARK: Decoding

    /** Decodes to a small blurry placeholder; null on a malformed hash. */
    fun decode(hash: String, width: Int, height: Int, punch: Double = 1.0): Bitmap? {
        if (hash.length < 6 || width <= 0 || height <= 0) return null
        val sizeFlag = decode83(hash.substring(0, 1)) ?: return null
        val numY = sizeFlag / 9 + 1
        val numX = sizeFlag % 9 + 1
        val quant = decode83(hash.substring(1, 2)) ?: return null
        val maximumValue = (quant + 1) / 166.0 * punch
        if (hash.length != 4 + 2 * numX * numY) return null

        val dc = decode83(hash.substring(2, 6)) ?: return null
        val colors = Array(numX * numY) { DoubleArray(3) }
        colors[0] = decodeDC(dc)
        for (i in 1 until numX * numY) {
            val start = 4 + i * 2
            val ac = decode83(hash.substring(start, start + 2)) ?: return null
            colors[i] = decodeAC(ac, maximumValue)
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (j in 0 until numY) {
                    for (i in 0 until numX) {
                        val basis = cos(Math.PI * x * i / width) * cos(Math.PI * y * j / height)
                        val color = colors[j * numX + i]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }
                pixels[y * width + x] = (0xFF shl 24) or
                    (linearToSRGB(r) shl 16) or
                    (linearToSRGB(g) shl 8) or
                    linearToSRGB(b)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // MARK: Helpers

    private fun sRGBToLinear(value: Int): Double {
        val v = value / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSRGB(value: Double): Int {
        val v = max(0.0, min(1.0, value))
        val srgb = if (v <= 0.0031308) v * 12.92 * 255 + 0.5
        else (1.055 * v.pow(1 / 2.4) - 0.055) * 255 + 0.5
        return max(0, min(255, srgb.toInt()))
    }

    private fun encodeDC(value: DoubleArray): Int =
        (linearToSRGB(value[0]) shl 16) + (linearToSRGB(value[1]) shl 8) + linearToSRGB(value[2])

    private fun encodeAC(value: DoubleArray, maximumValue: Double): Int {
        fun quantise(component: Double): Int {
            val scaled = signPow(component / maximumValue, 0.5) * 9 + 9.5
            return max(0, min(18, floor(scaled).toInt()))
        }
        return quantise(value[0]) * 19 * 19 + quantise(value[1]) * 19 + quantise(value[2])
    }

    private fun decodeDC(value: Int): DoubleArray = doubleArrayOf(
        sRGBToLinear((value shr 16) and 0xFF),
        sRGBToLinear((value shr 8) and 0xFF),
        sRGBToLinear(value and 0xFF),
    )

    private fun decodeAC(value: Int, maximumValue: Double): DoubleArray {
        fun dequantise(quantised: Int): Double =
            signPow((quantised - 9) / 9.0, 2.0) * maximumValue
        return doubleArrayOf(
            dequantise(value / (19 * 19)),
            dequantise((value / 19) % 19),
            dequantise(value % 19),
        )
    }

    private fun signPow(value: Double, exp: Double): Double =
        abs(value).pow(exp).withSign(value)

    private fun encode83(value: Int, length: Int): String {
        val chars = CharArray(length)
        var remaining = value
        for (i in length - 1 downTo 0) {
            chars[i] = alphabet[remaining % 83]
            remaining /= 83
        }
        return String(chars)
    }

    private fun decode83(value: String): Int? {
        var result = 0
        for (char in value) {
            val index = alphabet.indexOf(char)
            if (index < 0) return null
            result = result * 83 + index
        }
        return result
    }
}
