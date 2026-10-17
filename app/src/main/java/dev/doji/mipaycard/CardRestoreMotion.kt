package dev.doji.mipaycard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import dev.doji.mipaycard.data.WalletCard
import kotlinx.coroutines.delay

internal enum class CardRestorePhase {
    Idle,
    Shaking,
    Bursting,
    Appearing,
}

internal class CardRestoreMotion {
    var phase by mutableStateOf(CardRestorePhase.Idle)
    val burst = Animatable(0f)
    val scale = Animatable(1f)

    val showsPreviousCard: Boolean
        get() = phase == CardRestorePhase.Idle || phase == CardRestorePhase.Shaking || phase == CardRestorePhase.Bursting
}

@Composable
internal fun rememberCardRestoreMotion(
    restoring: Boolean,
    previousCard: WalletCard?,
    expanded: Boolean,
    onFinished: () -> Unit,
): CardRestoreMotion {
    val motion = remember { CardRestoreMotion() }
    val finished = rememberUpdatedState(onFinished)
    LaunchedEffect(restoring, previousCard, expanded) {
        motion.scale.snapTo(1f)
        motion.burst.snapTo(0f)
        if (!expanded) {
            motion.phase = CardRestorePhase.Idle
            if (previousCard != null) finished.value()
            return@LaunchedEffect
        }
        if (previousCard == null) {
            motion.phase = if (restoring) CardRestorePhase.Shaking else CardRestorePhase.Idle
            return@LaunchedEffect
        }

        // A fast restore can finish between frames. Always show the old face shaking first.
        motion.phase = CardRestorePhase.Shaking
        delay(180)
        motion.phase = CardRestorePhase.Bursting
        motion.burst.animateTo(1f, tween(90, easing = FastOutLinearInEasing))
        motion.scale.snapTo(0.86f)
        motion.phase = CardRestorePhase.Appearing
        motion.scale.animateTo(1.24f, tween(120, easing = FastOutSlowInEasing))
        motion.scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 1000f))
        motion.phase = CardRestorePhase.Idle
        finished.value()
    }
    return motion
}

internal fun Modifier.restoreCardMotion(motion: CardRestoreMotion): Modifier =
    graphicsLayer {
        val bursting = motion.phase == CardRestorePhase.Bursting
        val scale = if (bursting) 1f + motion.burst.value * 0.18f else motion.scale.value
        scaleX = scale
        scaleY = scale
        alpha = if (bursting) 1f - motion.burst.value else 1f
    }
