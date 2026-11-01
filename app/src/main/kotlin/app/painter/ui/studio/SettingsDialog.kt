package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.BuildConfig
import app.painter.R
import app.painter.ui.drive.DriveUiState

/** Full settings sheet: appearance, colour pipeline, stylus, Google Drive and the version/about block. */
@Composable
fun SettingsDialog(
    state: StudioUiState,
    actions: StudioActions,
    drive: DriveUiState = DriveUiState(),
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Section(stringResource(R.string.settings_appearance))
                SettingSwitch(
                    label = stringResource(R.string.light_background),
                    checked = state.lightBackground,
                    onChange = actions::setLightBackground,
                )
                SettingSwitch(
                    label = stringResource(R.string.double_tap_fit),
                    checked = state.doubleTapFits,
                    onChange = actions::setDoubleTapFits,
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Section(stringResource(R.string.settings_color))
                SettingSwitch(
                    label = stringResource(R.string.gamma_correct),
                    checked = state.gammaCorrect,
                    onChange = actions::setGammaCorrect,
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Section(stringResource(R.string.settings_timelapse))
                SettingSwitch(
                    label = stringResource(R.string.timelapse_record),
                    checked = state.timelapseEnabled,
                    onChange = actions::setTimelapseEnabled,
                )
                Text(
                    stringResource(R.string.timelapse_frames, state.timelapseFrames),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Section(stringResource(R.string.settings_stylus))
                TextButton(onClick = {
                    actions.closeSettings()
                    actions.toggleStylusPanel()
                }) { Text(stringResource(R.string.stylus_settings)) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Section(stringResource(R.string.drive_title))
                Text(
                    drive.account?.email ?: stringResource(R.string.drive_not_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    actions.closeSettings()
                    actions.openDrivePanel()
                }) {
                    Text(
                        stringResource(
                            if (drive.account == null) R.string.drive_connect else R.string.drive_manage,
                        ),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Section(stringResource(R.string.settings_about))
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_no_monetization),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
