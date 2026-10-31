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
import kotlin.math.roundToInt

/**
 * Contextual bar of the liquify tool (spec/ROADMAP.md M3): brush size and push strength as steppers, then
 * "Cancel" (puts the pushed pixels back) and "Apply" (keeps them as one undo step and returns to the brush).
 */
@Composable
fun LiquifyBar(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    PanelSurface(modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.tool_liquify),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.size), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { actions.setLiquifySize(state.liquifySize / 1.2f) }) { Text("−") }
                Text("${state.liquifySize.roundToInt()}", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { actions.setLiquifySize(state.liquifySize * 1.2f) }) { Text("+") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.strength), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { actions.setLiquifyStrength(state.liquifyStrength - 0.05f) }) { Text("−") }
                Text("${(state.liquifyStrength * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { actions.setLiquifyStrength(state.liquifyStrength + 0.05f) }) { Text("+") }
            }
            TextButton(onClick = {
                actions.finishLiquify(commit = false)
                actions.selectTool(ToolUi.BRUSH)
            }) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = {
                actions.finishLiquify(commit = true)
                actions.selectTool(ToolUi.BRUSH)
            }) { Text(stringResource(R.string.apply)) }
        }
    }
}
