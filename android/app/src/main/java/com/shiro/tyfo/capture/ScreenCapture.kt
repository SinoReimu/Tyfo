package com.shiro.tyfo.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.shiro.tyfo.backend.CaptureOps
import com.shiro.tyfo.backend.PrivilegeBackend
import com.shiro.tyfo.backend.NoBackend

/**
 * Orchestration layer for screen capture.
 *
 * Handles JPEG encoding, base64 conversion, and VirtualDisplay bitmap passthrough.
 * Actual privileged capture is delegated to a [PrivilegeBackend].
 *
 * [backend] is [NoBackend] until AgentService connects to the privileged backend.
 */
object ScreenCapture {

    private const val TAG = "ScreenCapture"

    @Volatile var backend: PrivilegeBackend = NoBackend

    /**
     * Capture the default (physical) display and return as base64 JPEG.
     */
    fun captureBase64(): String? = captureBase64(displayId = 0)

    /**
     * Capture a specific display and return as base64 JPEG.
     * @param displayId 0 for physical display, or a virtual display ID
     * @param virtualDisplayBitmap Optional pre-captured bitmap from VirtualDisplayManager
     * @param maxWidth scale down to at most this width (the model's screenshot size)
     * @param quality JPEG quality
     */
    fun captureBase64(
        displayId: Int = 0,
        virtualDisplayBitmap: Bitmap? = null,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        quality: Int = DEFAULT_QUALITY,
    ): String? {
        val bytes = captureJpegBytes(displayId, virtualDisplayBitmap, maxWidth, quality) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun captureJpegBytes(
        displayId: Int = 0,
        virtualDisplayBitmap: Bitmap? = null,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        quality: Int = DEFAULT_QUALITY,
    ): ByteArray? {
        if (virtualDisplayBitmap != null) {
            return try {
                ImageUtil.processScreenshotToBytes(virtualDisplayBitmap, maxWidth, quality)
            } finally {
                if (!virtualDisplayBitmap.isRecycled) virtualDisplayBitmap.recycle()
            }
        }
        return backend.captureScreen(displayId, maxWidth, quality)
    }

    private const val DEFAULT_MAX_WIDTH = 540
    private const val DEFAULT_QUALITY = 85

    /**
     * Capture a specific display as a Bitmap.
     *
     * Used by AgentEngine as a fallback when VirtualDisplay ImageReader capture fails.
     * Goes through the backend: JPEG bytes are decoded back to Bitmap.
     * The roundtrip quality loss is acceptable for a fallback path.
     */
    fun captureBitmap(displayId: Int = 0): Bitmap? {
        val bytes = backend.captureScreen(displayId, 0, 100) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
