package com.example.gemini.ui.opencode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.opencode.OpenCodeChatBackend
import com.example.gemini.theme.QuotaAmber
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import com.example.gemini.ui.components.OpenCodeAccent

/**
 * OpenCode status + runtime install screen (Antigravity-level depth).
 */
@Composable
fun OpenCodeSettingsScreen(
    backend: OpenCodeChatBackend,
    modifier: Modifier = Modifier
) {
    val bridgeStatus by backend.bridgeStatus.collectAsState()
    val installInfo by backend.installInfo.collectAsState()

    LaunchedEffect(Unit) {
        backend.refreshInstallInfo()
    }

    val status = installInfo?.optString("status")?.ifBlank { null } ?: bridgeStatus ?: "unknown"
    val installed = installInfo?.optBoolean("installed") == true
    val version = installInfo?.optString("version").orEmpty()
    val binPath = installInfo?.optString("bin_path").orEmpty()
    val platform = installInfo?.optString("platform").orEmpty()
    val android = installInfo?.optBoolean("android") == true
    val hint = installInfo?.optString("hint").orEmpty()
    val job = installInfo?.optJSONObject("job")
    val jobRunning = job?.optBoolean("running") == true
    val jobDone = job?.optBoolean("done") == true
    val jobError = job?.optString("error").orEmpty()
    val jobLog = job?.optString("log").orEmpty()

    val statusColor = when (status) {
        "online" -> QuotaGreen
        "starting", "retrying" -> QuotaAmber
        "error", "stopped" -> QuotaRed
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "OpenCode runs as `opencode serve` under the local bridge. Install the binary at runtime; it is not bundled with the app.",
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Serve status", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { backend.refreshInstallInfo() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            status.uppercase(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = statusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (installed) "Binary found" else "Binary missing",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (version.isNotBlank()) {
                    MetaRow("Version", version)
                }
                if (binPath.isNotBlank()) {
                    MetaRow("Path", binPath)
                }
                if (platform.isNotBlank()) {
                    MetaRow("Platform", platform + if (android) " (Android)" else "")
                }
            }
        }

        if (hint.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = OpenCodeAccent.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, OpenCodeAccent.copy(alpha = 0.25f))
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Info, null, tint = OpenCodeAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(hint, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

        Text("Runtime install", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            if (android) {
                "Official OpenCode builds are Linux/glibc and will not run on Android. Use a Termux community package or put a native binary on PATH, then refresh."
            } else {
                "Downloads the latest official binary from GitHub into ~/.opencode/bin and restarts serve."
            },
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { backend.startInstall() },
                enabled = !jobRunning && !android,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (jobRunning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Installing…")
                } else {
                    Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (installed) "Reinstall" else "Install OpenCode")
                }
            }
            OutlinedButton(
                onClick = { backend.refreshInstallInfo() },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Refresh")
            }
        }

        if (jobLog.isNotBlank() || jobError.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (jobDone && jobError.isBlank()) "Install log" else if (jobError.isNotBlank()) "Install error" else "Install progress",
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = if (jobError.isNotBlank()) QuotaRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (jobError.isNotBlank()) jobError + "\n\n" + jobLog else jobLog,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "After install, wait until status is ONLINE, then start a new OpenCode chat from the empty chat agent picker.",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 3)
    }
}

/** Compact card for the main Settings menu when OpenCode is enabled. */
@Composable
fun OpenCodeSettingsGroup(
    backend: OpenCodeChatBackend,
    onOpen: () -> Unit
) {
    val bridgeStatus by backend.bridgeStatus.collectAsState()
    val installInfo by backend.installInfo.collectAsState()
    LaunchedEffect(Unit) { backend.refreshInstallInfo() }

    val status = installInfo?.optString("status")?.ifBlank { null } ?: bridgeStatus ?: "…"
    val installed = installInfo?.optBoolean("installed") == true
    val statusColor = when (status) {
        "online" -> QuotaGreen
        "starting", "retrying" -> QuotaAmber
        "error", "stopped" -> QuotaRed
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    }
    val subtitle = buildString {
        append(status)
        if (installed) append(" · installed") else append(" · not installed")
    }

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, OpenCodeAccent.copy(alpha = 0.35f)),
        color = OpenCodeAccent.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OpenCode", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = OpenCodeAccent)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Surface(shape = RoundedCornerShape(50), color = statusColor.copy(alpha = 0.18f)) {
                Text(
                    status.uppercase(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }
        }
    }
}
