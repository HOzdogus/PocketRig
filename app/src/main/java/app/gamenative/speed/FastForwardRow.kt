package app.gamenative.speed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** PocketRig: game speed buttons at the top of the in-game menu's Tools tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FastForwardRow(modifier: Modifier = Modifier) {
    var speed by remember { mutableDoubleStateOf(SpeedControl.currentSpeed) }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Game speed (experimental)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (!SpeedControl.isActiveForSession) {
            Text(
                "Turn on Fast-forward in Settings, under Emulation, then start the game again. " +
                    "Works with Bionic containers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedControl.SPEEDS.forEach { s ->
                FilterChip(
                    selected = speed == s,
                    onClick = {
                        SpeedControl.setSpeed(s)
                        speed = s
                    },
                    label = { Text(if (s % 1.0 == 0.0) "${s.toInt()}×" else "$s×") },
                )
            }
        }
        Text(
            "Not for online games. Audio, videos and some physics may misbehave at higher speeds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
