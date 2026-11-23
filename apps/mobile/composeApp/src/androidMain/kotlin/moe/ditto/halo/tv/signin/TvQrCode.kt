package moe.ditto.halo.tv.signin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The QR code's modules for [text], with no margin of its own. */
internal fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(
        EncodeHintType.MARGIN to 0,
        // Medium: still reads with a glare across a glossy screen.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    ),
)

/**
 * A QR code a phone camera reads off a TV across a room: black on a white
 * card with the quiet zone the standard asks for, drawn module by module so
 * it stays sharp at any size.
 */
@Composable
internal fun TvQrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    Box(
        modifier
            .background(Color.White, RoundedCornerShape(16.dp))
            // Outside the padding, so the code's node includes its white margin.
            .semantics { contentDescription = "QR code for $text" }
            // Four modules of white all round, at the size drawn below.
            .padding(size * QuietModules / (matrix.width + 2 * QuietModules)),
    ) {
        val inner = size * matrix.width / (matrix.width + 2 * QuietModules)
        Canvas(Modifier.size(inner)) {
            val cell = this.size.width / matrix.width
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (!matrix[x, y]) continue
                    // A hair larger than a cell, so no seams show between modules.
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

private const val QuietModules = 4
