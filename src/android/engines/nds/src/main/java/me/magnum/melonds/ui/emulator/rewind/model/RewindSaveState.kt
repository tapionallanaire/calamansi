package me.magnum.melonds.ui.emulator.rewind.model

import android.graphics.Bitmap
import java.nio.ByteBuffer
import me.magnum.melonds.utils.DsScreenshotConverter

class RewindSaveState(
    val buffer: ByteBuffer,
    val bufferContentSize: Long,
    val screenshotBuffer: ByteBuffer,
    val frame: Int
) {
    val screenshot: Bitmap get() = DsScreenshotConverter.fromByteBufferToBitmap(screenshotBuffer)
}
