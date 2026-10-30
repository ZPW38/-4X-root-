package com.zpw38.watchroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zpw38.watchroot.R

/**
 * The one and only screen: what the app is doing, one primary action, and the live log.
 *
 * Layout rules that matter on a 1.4" watch display (~220 dp tall):
 *
 *  * Nothing inside the root [Column] may use `fillMaxSize()` — that measures the child against
 *    the *whole* viewport instead of the space left over, and everything below it is clipped away
 *    by the parent. The log therefore takes the leftover height with `weight(1f)`.
 *  * When the viewport is short the header, the info card and the button all shrink, so the log
 *    keeps at least a few lines. The same composable is used unchanged on a phone.
 */

/** What the app is currently doing. */
enum class RootUiState {
    IDLE,
    CONNECTING,
    PUSHING,
    RUNNING,
    SUCCESS,
    FAILED;

    /** Whether work is in flight, i.e. the primary action has to be disabled. */
    val busy: Boolean
        get() = this == CONNECTING || this == PUSHING || this == RUNNING
}

data class RootUiModel(
    val state: RootUiState = RootUiState.IDLE,
    val deviceName: String = "",
    val kernel: String = "",
    val endpoint: String = "",
    val progress: Float = 0f,
    val log: List<String> = emptyList(),
    val error: String = "",
)

@Composable
fun RootScreen(
    model: RootUiModel,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // A watch is roughly 220-320 dp tall; a phone is 700+ dp. Below this the layout
            // switches to its compact form instead of relying on the user to scroll.
            val compact = maxHeight < 400.dp
            val horizontal = if (compact) 12.dp else 16.dp
            val vertical = if (compact) 8.dp else 16.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxSize()
                        .padding(horizontal = horizontal, vertical = vertical),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                ) {
                    Header(model, compact)

                    if (compact) {
                        CompactInfoLine(model)
                    } else {
                        DeviceCard(model)
                    }

                    when (model.state) {
                        RootUiState.SUCCESS -> SuccessCard()
                        RootUiState.FAILED -> ErrorBlock(model.error, onRetry)
                        else -> ActionBlock(model, compact, onStart)
                    }

                    // weight(1f), never fillMaxSize(): see the note at the top of the file.
                    LogCard(model, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- header

@Composable
private fun Header(model: RootUiModel, compact: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.title_root),
            style = if (compact) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.headlineSmall
            },
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.weight(1f))
        StatusChip(model.state, compact)
    }
}

/** Status pill: the only element that changes colour with the run state. */
@Composable
private fun StatusChip(state: RootUiState, compact: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = statusColors(state)
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = stateLabel(state),
            style = if (compact) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.labelLarge
            },
            modifier = Modifier.padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 4.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun statusColors(state: RootUiState): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        RootUiState.IDLE -> scheme.surfaceVariant to scheme.onSurfaceVariant
        RootUiState.CONNECTING, RootUiState.PUSHING, RootUiState.RUNNING ->
            scheme.tertiaryContainer to scheme.onTertiaryContainer
        RootUiState.SUCCESS -> scheme.primaryContainer to scheme.onPrimaryContainer
        RootUiState.FAILED -> scheme.errorContainer to scheme.onErrorContainer
    }
}

@Composable
private fun stateLabel(state: RootUiState): String = stringResource(
    when (state) {
        RootUiState.IDLE -> R.string.state_idle
        RootUiState.CONNECTING -> R.string.state_connecting
        RootUiState.PUSHING -> R.string.state_pushing
        RootUiState.RUNNING -> R.string.state_running
        RootUiState.SUCCESS -> R.string.state_success
        RootUiState.FAILED -> R.string.state_failed
    },
)

// ---------------------------------------------------------------------------- device info

@Composable
private fun DeviceCard(model: RootUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InfoRow(stringResource(R.string.label_device), model.deviceName.ifBlank { "—" })
            InfoRow(stringResource(R.string.label_kernel), model.kernel.ifBlank { "—" })
            InfoRow(stringResource(R.string.label_adb), model.endpoint.ifBlank { "—" })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            InfoRow(stringResource(R.string.label_state), stateLabel(model.state))
        }
    }
}

/**
 * One-line variant for a watch: the model and the kernel are what decide whether the payload can
 * run, so they survive; everything else is dropped.
 */
@Composable
private fun CompactInfoLine(model: RootUiModel) {
    Text(
        text = buildString {
            append(model.deviceName.ifBlank { "—" })
            append(" · ")
            append(model.kernel.ifBlank { "—" })
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------- action

@Composable
private fun ActionBlock(model: RootUiModel, compact: Boolean, onStart: () -> Unit) {
    val busy = model.state.busy
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onStart,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 48.dp else 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = stringResource(R.string.action_start),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (busy) {
            LinearProgressIndicator(
                progress = { model.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun SuccessCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.state_success),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.hint_success),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ErrorBlock(error: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.state_failed),
                style = MaterialTheme.typography.titleMedium,
            )
            if (error.isNotBlank()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

// ---------------------------------------------------------------------------- log

/**
 * The live log. `LazyColumn` with `reverseLayout` keeps the newest line at the bottom without any
 * scroll-animation effect, and only composes what fits, which matters on a watch.
 */
@Composable
private fun LogCard(model: RootUiModel, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.label_log),
                style = MaterialTheme.typography.labelLarge,
            )
            HorizontalDivider(
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )
            if (model.log.isEmpty()) {
                Text(
                    text = stringResource(R.string.log_empty),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val state = rememberLazyListState()
                LazyColumn(
                    state = state,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(model.log.asReversed().take(LAST_LINES)) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** Hard cap so a payload that spams output cannot grow the list without bound. */
private const val LAST_LINES = 300
