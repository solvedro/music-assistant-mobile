package io.music_assistant.client.ui.compose.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.music_assistant.client.ui.compose.settings.SectionCard
import io.music_assistant.client.ui.compose.settings.SectionTitle

@Composable
fun WireGuardSettingsSection(
    enabled: Boolean,
    config: String,
    status: String?,
    onEnabledChange: (Boolean) -> Unit,
    onConfigChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftConfig by remember(config) { mutableStateOf(config) }
    var expanded by remember { mutableStateOf(false) }

    SectionCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = "WireGuard",
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        SectionTitle("WireGuard Tunnel (In-Process)")
                        Text(
                            text = status ?: if (enabled) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status?.startsWith("Connected") == true) {
                                MaterialTheme.colorScheme.primary
                            } else if (status?.startsWith("Failed") == true) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        if (checked && draftConfig != config) {
                            onConfigChange(draftConfig)
                        }
                        onEnabledChange(checked)
                    },
                )
            }

            AnimatedVisibility(visible = !enabled || expanded || config.isBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Text(
                        text = "WireGuard Client Configuration (.conf)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )

                    OutlinedTextField(
                        value = draftConfig,
                        onValueChange = { draftConfig = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 10,
                        placeholder = {
                            Text(
                                "[Interface]\nPrivateKey = <key>\nAddress = 10.0.0.2/24\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = <key>\nEndpoint = myhome.example.com:51820\nAllowedIPs = 192.168.100.0/24",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                onConfigChange(draftConfig)
                                if (!enabled && draftConfig.isNotBlank()) {
                                    onEnabledChange(true)
                                }
                            },
                            enabled = draftConfig.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (!enabled) "Save & Connect" else "Save Config",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }

                        if (draftConfig.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    draftConfig = ""
                                    onConfigChange("")
                                    if (enabled) {
                                        onEnabledChange(false)
                                    }
                                },
                            ) {
                                Text("Clear")
                            }
                        }
                    }

                    Text(
                        text = "Runs userspace WireGuard inside the app. No Android VPN permission or root needed; won't disconnect or clash with system VPNs. All app control and audio streams route directly through the tunnel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            if (enabled && !expanded && config.isNotBlank()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Edit Configuration")
                }
            } else if (enabled && expanded) {
                OutlinedButton(
                    onClick = { expanded = false },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Hide Configuration")
                }
            }
        }
    }
}
