package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.ui.components.PanelSurface

/**
 * Contextual bar of the transform tool, shown at the top centre while a session is open:
 * "Cancel" restores the original pixels, "Apply" records the transform as one undoable step.
 */
@Composable
fun TransformBar(actions: StudioActions, modifier: Modifier = Modifier) {
    PanelSurface(modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.tool_transform),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
            TextButton(onClick = actions::transformCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = actions::transformApply) { Text(stringResource(R.string.action_apply)) }
        }
    }
}
