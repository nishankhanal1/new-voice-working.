package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.VoiceState

@Composable
fun MicButtonWithRipples(
    voiceState: VoiceState,
    amplitude: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_ripple")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_pulse"
    )

    val ringPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_phase"
    )

    val isListening = voiceState == VoiceState.LISTENING
    val isSpeaking = voiceState == VoiceState.SPEAKING

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .testTag("mic_button_container")
            .size(170.dp)
    ) {
        // Concentric dotted / dashed audio rings as in the screenshot
        Canvas(modifier = Modifier.size(170.dp)) {
            val centerOffset = center
            val baseRadius = 46.dp.toPx()
            val maxRadius = size.minDimension / 2f

            val ringCount = 3
            for (i in 0 until ringCount) {
                val stepFraction = (i + 1).toFloat() / (ringCount + 1)
                val currentRadius = baseRadius + (maxRadius - baseRadius) * stepFraction

                val dynamicExpansion = if (isListening) {
                    (amplitude * 14.dp.toPx()) + ((ringPhase * 8.dp.toPx() + i * 4.dp.toPx()) % 12.dp.toPx())
                } else if (isSpeaking) {
                    amplitude * 8.dp.toPx()
                } else {
                    0f
                }

                val ringAlpha = when {
                    isListening -> (0.28f + (amplitude * 0.45f) - (stepFraction * 0.12f)).coerceIn(0.1f, 0.85f)
                    isSpeaking -> 0.22f
                    else -> 0.15f - (stepFraction * 0.05f)
                }

                drawCircle(
                    color = Color(0xFFC084FC).copy(alpha = ringAlpha),
                    radius = currentRadius + dynamicExpansion,
                    center = centerOffset,
                    style = Stroke(
                        width = 1.4.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            intervals = floatArrayOf(
                                3.dp.toPx(),
                                (4 + i * 2).dp.toPx()
                            ),
                            phase = ringPhase * 10f
                        )
                    )
                )
            }
        }

        // Ambient glow behind mic
        if (isListening || isSpeaking) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(if (isListening) pulseScale + amplitude * 0.25f else 1.05f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0x77A855F7),
                                Color(0x337C3AED),
                                Color(0x00000000)
                            )
                        )
                    )
            )
        }

        // Main Mic Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .scale(if (isListening) pulseScale else 1f)
                .shadow(
                    elevation = if (isListening) 16.dp else 10.dp,
                    shape = CircleShape,
                    ambientColor = Color(0xFFC084FC),
                    spotColor = Color(0xFFA855F7)
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (isListening) {
                            listOf(
                                Color(0xFF6B21A8),
                                Color(0xFF3B0764),
                                Color(0xFF1E073B)
                            )
                        } else {
                            listOf(
                                Color(0xFF4C1D95),
                                Color(0xFF2E1065),
                                Color(0xFF1A0A38)
                            )
                        }
                    )
                )
                .border(
                    width = 1.8.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFFE9D5FF),
                            Color(0xFFA855F7),
                            Color(0xFF581C87)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = Color.White),
                    onClick = onClick
                )
                .testTag("mic_toggle_button")
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isListening) "Stop Listening" else "Start Listening",
                tint = if (isListening) Color(0xFFF472B6) else Color(0xFFF3E8FF),
                modifier = Modifier.size(34.dp)
            )
        }
    }
}
