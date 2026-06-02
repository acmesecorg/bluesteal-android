package org.crainiate.bluesteal.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/** Recognises the FIDO hybrid QR scheme. We share the raw URI; we don't decode the CBOR. */
object FidoQR {
    fun isFidoUri(s: String): Boolean = s.uppercase().startsWith("FIDO:/")
}

/** Encode a string as a QR bitmap via ZXing. */
fun generateQrBitmap(text: String, size: Int = 512): Bitmap? = try {
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val offset = y * w
        for (x in 0 until w) pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
    }
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
} catch (e: Exception) {
    null
}

/** Renders a string as a scannable QR image. */
@Composable
fun QrCodeImage(uri: String, modifier: Modifier = Modifier) {
    val bitmap = remember(uri) { generateQrBitmap(uri) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code",
            modifier = modifier,
            filterQuality = FilterQuality.None   // keep modules crisp
        )
    } else {
        Text("Couldn’t render QR", modifier = modifier, color = MaterialTheme.colorScheme.error)
    }
}

/** Shown after a successful scan: the rendered QR plus Copy / Send actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannedResultSheet(
    uri: String,
    canSend: Boolean,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Scanned FIDO QR", style = MaterialTheme.typography.titleMedium)
            QrCodeImage(uri, Modifier.size(220.dp))
            Text(
                uri,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCopy) { Text("Copy") }
                if (canSend) {
                    Button(onClick = { onSend(); onDismiss() }) { Text("Send") }
                }
            }
            if (!canSend) {
                Text(
                    "Connect to a peer (Host or join) to send.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The slide-up modal shown on the receiving device: the peer's QR to scan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedQrSheet(uri: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("QR from peer", style = MaterialTheme.typography.titleMedium)
            Text(
                "Scan this with your phone",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            QrCodeImage(uri, Modifier.size(260.dp))
            Button(onClick = onDismiss) { Text("Done") }
        }
    }
}
