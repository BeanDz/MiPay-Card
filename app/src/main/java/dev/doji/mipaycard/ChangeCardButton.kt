package dev.doji.mipaycard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

private const val RESTORE_HOLD_MILLIS = 500L

@Composable
internal fun ChangeCardButton(
    enabled: Boolean,
    restoring: Boolean,
    onChange: () -> Unit,
    onRestore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val changeCallback = rememberUpdatedState(onChange)
    val restoreCallback = rememberUpdatedState(onRestore)
    val restoreLabel = stringResource(R.string.restore)
    val holdDescription = stringResource(R.string.change_hold_restore)
    val colors = ButtonDefaults.filledTonalButtonColors()
    val colorScheme = MaterialTheme.colorScheme

    FilledTonalButton(
        onClick = onChange,
        enabled = enabled,
        colors =
            if (restoring) {
                colors.copy(
                    containerColor = colorScheme.error,
                    contentColor = colorScheme.onError,
                    disabledContainerColor = colorScheme.error,
                    disabledContentColor = colorScheme.onError,
                )
            } else {
                colors
            },
        interactionSource = interactions,
        modifier =
            modifier
                .semantics {
                    if (onRestore != null) stateDescription = holdDescription
                    if (enabled && onRestore != null) {
                        onLongClick(label = restoreLabel) {
                            val restore = restoreCallback.value ?: return@onLongClick false
                            restore()
                            true
                        }
                    }
                }.pointerInput(enabled, onRestore != null) {
                    if (!enabled) return@pointerInput

                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        down.consume()
                        val press = PressInteraction.Press(down.position)
                        interactions.tryEmit(press)
                        var up: PointerInputChange? = null
                        try {
                            val completed =
                                withTimeoutOrNull(RESTORE_HOLD_MILLIS) {
                                    up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                    true
                                }
                            if (completed == null) {
                                restoreCallback.value?.invoke()
                                while (currentEvent.changes.any { it.pressed }) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                                return@awaitEachGesture
                            }

                            val release = up ?: return@awaitEachGesture
                            release.consume()
                            if (release.uptimeMillis - down.uptimeMillis < RESTORE_HOLD_MILLIS) {
                                changeCallback.value()
                            } else {
                                restoreCallback.value?.invoke()
                            }
                        } finally {
                            val end = if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                            interactions.tryEmit(end)
                        }
                    }
                },
    ) {
        Text(stringResource(if (restoring) R.string.restoring_button_label else R.string.change))
    }
}
