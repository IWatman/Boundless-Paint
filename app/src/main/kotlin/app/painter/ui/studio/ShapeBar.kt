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
 * Contextual bar of the shape tool, shown at the top centre while a draft is open: the "Outline" and "Fill"
 * toggles (the fill only for closed shapes), "Stamp" (records a copy and keeps the draft), "Cancel" (drops the
 * draft) and "Apply" (records it as one undoable step).
 */
@Composable
fun ShapeBar(shape: ShapeUi, actions: StudioActions, modifier: Modifier = Modifier) {
    PanelSurface(modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.shape_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
            ToggleChip(stringResource(R.string.shape_outline), shape.outlineOn) {
                actions.setShapeOutline(!shape.outlineOn)
            }
            if (shape.closed) {
                ToggleChip(stringResource(R.string.shape_fill), shape.fillOn) {
                    actions.setShapeFill(!shape.fillOn)
                }
            }
            TextButton(onClick = actions::shapeStamp) { Text(stringResource(R.string.shape_stamp)) }
            TextButton(onClick = actions::shapeCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = actions::shapeCommit) { Text(stringResource(R.string.action_apply)) }
        }
    }
}
