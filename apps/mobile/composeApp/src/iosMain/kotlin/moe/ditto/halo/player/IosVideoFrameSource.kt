@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.ditto.halo.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.loadTracksWithMediaType
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL

/**
 * iOS scrub-preview reader over AVFoundation, separate from the libmpv core
 * that is playing. Platform decoding is deliberately best effort: a source
 * AVFoundation cannot open returns no frame and leaves the existing placeholder.
 */
internal object IosVideoFrameSource : VideoFrameSource {
    override suspend fun open(url: String): VideoFrameReader? {
        val sourceUrl = if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            NSURL.URLWithString(url)
        } else {
            // Finished downloads enter the shared route as absolute paths, not
            // file URLs. Treating one as a relative URL makes AVURLAsset look
            // for a network scheme and silently yield no frames.
            NSURL.fileURLWithPath(url)
        } ?: return null
        return try {
            val asset = AVURLAsset(sourceUrl, null)
            if (asset.hasVideoTrack()) IosVideoFrameReader(asset) else null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }
    }
}

/**
 * Track discovery can read a remote container header. Apple's iOS 15 loading
 * API performs that work asynchronously, and the callback is ignored if the
 * scrub session was cancelled while it was in flight.
 */
private suspend fun AVURLAsset.hasVideoTrack(): Boolean =
    suspendCancellableCoroutine { continuation ->
        loadTracksWithMediaType(AVMediaTypeVideo) { tracks, error ->
            when {
                !continuation.isActive -> Unit
                error != null -> continuation.resume(false)
                else -> continuation.resume(!tracks.isNullOrEmpty())
            }
        }
    }

@OptIn(ExperimentalAtomicApi::class)
private class IosVideoFrameReader(asset: AVURLAsset) : VideoFrameReader {
    private val generator = AVAssetImageGenerator(asset).apply {
        appliesPreferredTrackTransform = true
        requestedTimeToleranceBefore = FrameTolerance
        requestedTimeToleranceAfter = FrameTolerance
    }

    private val closed = AtomicBoolean(false)

    override suspend fun frameAt(
        positionSeconds: Double,
        widthPx: Int,
        heightPx: Int,
    ): ImageBitmap? {
        if (closed.load() || widthPx <= 0 || heightPx <= 0) return null
        return withContext(Dispatchers.Default) {
            if (closed.load()) return@withContext null
            generator.maximumSize = platform.CoreGraphics.CGSizeMake(
                widthPx.toDouble(),
                heightPx.toDouble(),
            )
            try {
                val image = generator.copyCGImageAtTime(
                    requestedTime = CMTimeMakeWithSeconds(positionSeconds.coerceAtLeast(0.0), TimeScale),
                    actualTime = null,
                    error = null,
                ) ?: return@withContext null
                try {
                    image.toImageBitmap().takeUnless { closed.load() }
                } finally {
                    CGImageRelease(image)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }
        }
    }

    override fun close() {
        if (closed.exchange(true)) return
        generator.cancelAllCGImageGeneration()
    }

    private companion object {
        const val TimeScale = 600
        val FrameTolerance = CMTimeMakeWithSeconds(10.0, TimeScale)
    }
}

/** Copies a Core Graphics frame into the BGRA raster Compose draws natively. */
private fun CGImageRef.toImageBitmap(): ImageBitmap? {
    val width = CGImageGetWidth(this).toInt()
    val height = CGImageGetHeight(this).toInt()
    if (width <= 0 || height <= 0) return null

    val rowBytes = width * BytesPerPixel
    val pixels = ByteArray(rowBytes * height)
    val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
    try {
        val rendered = pixels.usePinned { pinned ->
            val context = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = BitsPerComponent.toULong(),
                bytesPerRow = rowBytes.toULong(),
                space = colorSpace,
                bitmapInfo = kCGBitmapByteOrder32Little or
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value,
            ) ?: return@usePinned false
            try {
                // Core Graphics is bottom-up; Skia's first row is the top.
                CGContextTranslateCTM(context, 0.0, height.toDouble())
                CGContextScaleCTM(context, 1.0, -1.0)
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), this)
                true
            } finally {
                CGContextRelease(context)
            }
        }
        if (!rendered) return null
    } finally {
        CGColorSpaceRelease(colorSpace)
    }

    val imageInfo = ImageInfo(
        width = width,
        height = height,
        colorType = ColorType.BGRA_8888,
        alphaType = ColorAlphaType.PREMUL,
        colorSpace = ColorSpace.sRGB,
    )
    val skiaImage = Image.makeRaster(imageInfo, pixels, rowBytes)
    return try {
        skiaImage.toComposeImageBitmap()
    } finally {
        skiaImage.close()
    }
}

private const val BytesPerPixel = 4
private const val BitsPerComponent = 8
