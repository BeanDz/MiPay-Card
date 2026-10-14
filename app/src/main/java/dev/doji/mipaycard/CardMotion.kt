package dev.doji.mipaycard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DeferredTargetAnimation
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlinx.coroutines.isActive
import kotlin.random.Random

internal const val CARD_SPRING_DAMPING = 0.65f

private val cardPositionSpring =
    spring<IntOffset>(dampingRatio = CARD_SPRING_DAMPING, stiffness = Spring.StiffnessMediumLow)

@Composable
internal fun Modifier.shakeWhileRestoring(restoring: Boolean): Modifier {
    val shake = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(restoring) {
        if (!restoring) {
            shake.animateTo(Offset.Zero, tween(durationMillis = 100, easing = FastOutSlowInEasing))
            return@LaunchedEffect
        }

        while (isActive) {
            shake.animateTo(
                targetValue = Offset(Random.nextFloat() * 2f - 1f, Random.nextFloat() * 2f - 1f),
                animationSpec = tween(durationMillis = Random.nextInt(8, 17), easing = FastOutSlowInEasing),
            )
        }
    }
    return graphicsLayer {
        val amplitude = 6.dp.toPx()
        translationX = shake.value.x * amplitude
        translationY = shake.value.y * amplitude
    }
}

internal class CardArrival {
    private var targetY: Int? = null
    private var previousRemainingY: Int? = null
    private var reported = false

    fun update(
        target: Int,
        position: Int,
    ): Boolean {
        if (targetY != target) {
            targetY = target
            previousRemainingY = null
            reported = false
        }

        val remaining = target - position
        val previous = previousRemainingY
        previousRemainingY = remaining
        if (reported) return false
        if (remaining != 0 && (previous == null || remaining.compareTo(0) == previous.compareTo(0))) return false

        reported = true
        return true
    }
}

@Composable
internal fun Modifier.animateCardPosition(
    lookaheadScope: LookaheadScope,
    onArrived: () -> Unit,
): Modifier {
    val animation = remember { DeferredTargetAnimation(IntOffset.VectorConverter) }
    val arrival = remember { CardArrival() }
    val coroutineScope = rememberCoroutineScope()
    val arrivedCallback = rememberUpdatedState(onArrived)

    return approachLayout(
        isMeasurementApproachInProgress = { false },
        isPlacementApproachInProgress = { coordinates ->
            val target =
                with(lookaheadScope) {
                    lookaheadScopeCoordinates
                        .localLookaheadPositionOf(coordinates, includeMotionFrameOfReference = false)
                        .round()
                }
            val animatedPosition = animation.updateTarget(target, coroutineScope, cardPositionSpring)
            if (arrival.update(target.y, animatedPosition.y)) arrivedCallback.value()
            !animation.isIdle
        },
    ) { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            val coordinates = coordinates
            if (coordinates == null) {
                placeable.place(0, 0)
                return@layout
            }

            with(lookaheadScope) {
                val target =
                    lookaheadScopeCoordinates
                        .localLookaheadPositionOf(coordinates, includeMotionFrameOfReference = false)
                        .round()
                val animatedPosition = animation.updateTarget(target, coroutineScope, cardPositionSpring)
                val currentPosition =
                    lookaheadScopeCoordinates
                        .localPositionOf(coordinates, Offset.Zero, includeMotionFrameOfReference = false)
                        .round()
                val offset = animatedPosition - currentPosition
                placeable.place(offset.x, offset.y)
            }
        }
    }
}
