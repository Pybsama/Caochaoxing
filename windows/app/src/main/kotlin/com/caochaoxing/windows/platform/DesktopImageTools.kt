// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.platform

import com.caochaoxing.core.crypto.PixelImage
import com.caochaoxing.core.util.ImageObfuscationHelpers
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pure JVM desktop image obfuscation for anti-duplication photo check-in:
 * subtle random border crop + resize back, sparse pixel perturbation, JPEG re-encoding,
 * and standard JPEG `COM` UUID comment insertion.
 */
object DesktopImageObfuscator {
    private const val MAX_EDGE_PX = 1600
    private const val JPEG_QUALITY = 0.88f

    fun obfuscate(rawBytes: ByteArray): ByteArray {
        val original = runCatching {
            ImageIO.read(ByteArrayInputStream(rawBytes))
        }.getOrNull() ?: return ImageObfuscationHelpers.appendRandomJpegComment(rawBytes)

        val srcW = original.width
        val srcH = original.height
        if (srcW < 8 || srcH < 8) {
            return ImageObfuscationHelpers.appendRandomJpegComment(rawBytes)
        }

        // 1. Subtle 1%..3% random edge crop.
        val cropFrac = Random.nextDouble(0.01, 0.03)
        val cropX = max(1, (srcW * cropFrac * Random.nextDouble(0.3, 1.0)).roundToInt()).coerceAtMost(srcW / 8)
        val cropY = max(1, (srcH * cropFrac * Random.nextDouble(0.3, 1.0)).roundToInt()).coerceAtMost(srcH / 8)
        val cropW = (srcW - cropX * 2).coerceAtLeast(4)
        val cropH = (srcH - cropY * 2).coerceAtLeast(4)
        val cropped = original.getSubimage(cropX, cropY, cropW, cropH)

        // 2. Compute target output dimensions capped at MAX_EDGE_PX.
        val maxSide = max(srcW, srcH)
        val scale = if (maxSide > MAX_EDGE_PX) MAX_EDGE_PX.toDouble() / maxSide.toDouble() else 1.0
        val outW = max(4, (srcW * scale).roundToInt())
        val outH = max(4, (srcH * scale).roundToInt())

        val canvas = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(cropped, 0, 0, outW, outH, null)
        } finally {
            g.dispose()
        }

        // 3. Sparse pixel jitter (+-1..2 channel value on 16..28 pixels).
        val jitterCount = Random.nextInt(16, 29)
        repeat(jitterCount) {
            val px = Random.nextInt(0, outW)
            val py = Random.nextInt(0, outH)
            val rgb = canvas.getRGB(px, py)
            val r = (((rgb ushr 16) and 0xFF) + Random.nextInt(-2, 3)).coerceIn(0, 255)
            val gVal = (((rgb ushr 8) and 0xFF) + Random.nextInt(-2, 3)).coerceIn(0, 255)
            val b = ((rgb and 0xFF) + Random.nextInt(-2, 3)).coerceIn(0, 255)
            canvas.setRGB(px, py, (r shl 16) or (gVal shl 8) or b)
        }

        // 4. Encode as JPEG + append valid JPEG COM UUID block before EOI.
        val jpegBytes = encodeJpeg(canvas, JPEG_QUALITY)
        return ImageObfuscationHelpers.appendRandomJpegComment(jpegBytes)
    }

    fun normalizeToJpeg(rawBytes: ByteArray): ByteArray {
        val original = runCatching {
            ImageIO.read(ByteArrayInputStream(rawBytes))
        }.getOrNull() ?: return rawBytes
        val outW = original.width
        val outH = original.height
        val maxSide = max(outW, outH)
        val scale = if (maxSide > MAX_EDGE_PX) MAX_EDGE_PX.toDouble() / maxSide.toDouble() else 1.0
        val targetW = max(1, (outW * scale).roundToInt())
        val targetH = max(1, (outH * scale).roundToInt())
        val canvas = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(original, 0, 0, targetW, targetH, null)
        } finally {
            g.dispose()
        }
        return encodeJpeg(canvas, JPEG_QUALITY)
    }

    private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val out = ByteArrayOutputStream()
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (writers.hasNext()) {
            val writer = writers.next()
            try {
                val ios = ImageIO.createImageOutputStream(out)
                writer.output = ios
                val param = writer.defaultWriteParam
                if (param.canWriteCompressed()) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = quality.coerceIn(0.5f, 0.98f)
                }
                writer.write(null, IIOImage(image, null, null), param)
                ios.close()
            } finally {
                writer.dispose()
            }
        } else {
            ImageIO.write(image, "jpg", out)
        }
        return out.toByteArray()
    }
}

/**
 * Pure JVM ZXing QR decoder for local image files and clipboard screenshots.
 */
object DesktopQrCodeDecoder {
    private val decodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun decode(imageBytes: ByteArray): String? {
        val image = runCatching {
            ImageIO.read(ByteArrayInputStream(imageBytes))
        }.getOrNull() ?: return null
        return decode(image)
    }

    fun decode(image: BufferedImage): String? {
        // Attempt direct decode first, then scaled down if very large, then scaled up if small.
        decodeSingle(image)?.let { return it }

        val maxDim = max(image.width, image.height)
        if (maxDim > 1400) {
            val scaled = scaleImage(image, 1200.0 / maxDim.toDouble())
            decodeSingle(scaled)?.let { return it }
        }
        if (min(image.width, image.height) < 360) {
            val scaled = scaleImage(image, 2.0)
            decodeSingle(scaled)?.let { return it }
        }
        return null
    }

    private fun decodeSingle(image: BufferedImage): String? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)
        val source: LuminanceSource = RGBLuminanceSource(w, h, pixels)
        val reader = MultiFormatReader()
        val bitmaps = listOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
        )
        for (bitmap in bitmaps) {
            try {
                val result = reader.decode(bitmap, decodeHints)
                val text = result.text?.trim()
                if (!text.isNullOrEmpty()) return text
            } catch (_: NotFoundException) {
                // Try alternate binarizer.
            } catch (_: Throwable) {
                // Ignore malformed region errors.
            } finally {
                reader.reset()
            }
        }
        return null
    }

    private fun scaleImage(src: BufferedImage, factor: Double): BufferedImage {
        val w = max(1, (src.width * factor).roundToInt())
        val h = max(1, (src.height * factor).roundToInt())
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return dst
    }
}

/**
 * Decodes captcha shade/cutout image bytes into `:core` [PixelImage] buffers.
 */
object DesktopCaptchaImageDecoder {
    fun decode(bytes: ByteArray): PixelImage? {
        val img = runCatching {
            ImageIO.read(ByteArrayInputStream(bytes))
        }.getOrNull() ?: return null
        val w = img.width
        val h = img.height
        if (w <= 0 || h <= 0 || w > PixelImage.MAX_DIMENSION || h > PixelImage.MAX_DIMENSION) {
            return null
        }
        if (w.toLong() * h.toLong() > PixelImage.MAX_PIXELS.toLong()) {
            return null
        }
        val argb = IntArray(w * h)
        img.getRGB(0, 0, w, h, argb, 0, w)
        return PixelImage(width = w, height = h, argbPixels = argb)
    }
}
