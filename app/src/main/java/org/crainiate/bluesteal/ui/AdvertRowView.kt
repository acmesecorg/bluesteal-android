package org.crainiate.bluesteal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.crainiate.bluesteal.models.AdvertVariant
import org.crainiate.bluesteal.models.CableAdvert

enum class AdvertSource { Local, Relay }

@Composable
fun AdvertRowView(
    advert: CableAdvert,
    source: AdvertSource,
    isRebroadcasting: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Source / re-broadcasting indicator. Mirrors the macOS green-dot
        // treatment for adverts we are currently re-emitting on BLE.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isRebroadcasting -> Color(0xFF4CAF50)
                        source == AdvertSource.Relay -> Color(0xFF03A9F4)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    }
                )
        )
        VariantBadge(variant = advert.variant)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                advert.payloadHex,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "rssi ${advert.rssi} · count ${advert.count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                advert.localName?.takeIf { it.isNotBlank() }?.let {
                    Text(it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun VariantBadge(variant: AdvertVariant) {
    val (bg, fg) = when (variant) {
        AdvertVariant.FidoV2 -> Color(0xFF4FC3F7) to Color.Black
        AdvertVariant.GoogleV1 -> Color(0xFFB39DDB) to Color.Black
        AdvertVariant.GoogleV2 -> Color(0xFF81C784) to Color.Black
        AdvertVariant.GoogleMfgV1 -> Color(0xFFFFB74D) to Color.Black
        is AdvertVariant.Unknown -> Color.Gray to Color.White
    }
    Surface(color = bg.copy(alpha = 0.25f), shape = RoundedCornerShape(4.dp)) {
        Text(
            variant.badge,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
