package `in`.santhaliastore.ratecard.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import `in`.santhaliastore.ratecard.R

/**
 * Sync status indicator shown in the home top app bar.
 *
 * Tap behaviour: triggers an immediate sync on every state except
 * "in progress" (where it would be a no-op).
 */
@Stable
enum class SyncStatus { Synced, Pending, InProgress, Offline, Error }

@Composable
fun SyncStatusIcon(
    status: SyncStatus,
    onClick: () -> Unit
) {
    val (icon, contentDesc, tint) = mapping(status)
    IconButton(onClick = onClick, enabled = status != SyncStatus.InProgress) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = tint
        )
    }
}

@Composable
private fun mapping(status: SyncStatus): Triple<ImageVector, String, Color> = when (status) {
    SyncStatus.Synced -> Triple(
        Icons.Filled.CheckCircle,
        stringResource(R.string.sync_status_synced),
        MaterialTheme.colorScheme.tertiary
    )
    SyncStatus.Pending -> Triple(
        Icons.Outlined.CloudUpload,
        stringResource(R.string.sync_status_pending),
        MaterialTheme.colorScheme.secondary
    )
    SyncStatus.InProgress -> Triple(
        Icons.Filled.Sync,
        stringResource(R.string.sync_status_in_progress),
        MaterialTheme.colorScheme.primary
    )
    SyncStatus.Offline -> Triple(
        Icons.Filled.CloudOff,
        stringResource(R.string.sync_status_offline),
        MaterialTheme.colorScheme.onSurfaceVariant
    )
    SyncStatus.Error -> Triple(
        Icons.Filled.ErrorOutline,
        stringResource(R.string.sync_status_error),
        MaterialTheme.colorScheme.error
    )
}
