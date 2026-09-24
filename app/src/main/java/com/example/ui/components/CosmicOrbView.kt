package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.VoiceState

@Composable
fun CosmicOrbView(
    voiceState: VoiceState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cosmic_animations")

    // Continuous subtle cosmic rotation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (voiceState == VoiceState.PROCESSING) 4000 else 24000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_rotation"
    )

    // Breathing pulse scale
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (voiceState == VoiceState.LISTENING) 1200 else 2800,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    // Fluid organic expansion ripples for Voice harmonics
    val rippleProgress1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fluid_ripple_1"
    )

    val rippleProgress2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fluid_ripple_2"
    )

    val dynamicScale = when (voiceState) {
        VoiceState.LISTENING -> pulseScale + (amplitude * 0.16f)
        VoiceState.SPEAKING -> 1.0f + (amplitude * 0.20f)
        VoiceState.PROCESSING -> pulseScale
        else -> 1.0f
    }

    val statePrimaryColor = when (voiceState) {
        VoiceState.LISTENING -> Color(0xFF38BDF8) // Cyan / Sky Blue
        VoiceState.SPEAKING -> Color(0xFFC084FC) // Electric Purple
        VoiceState.PROCESSING -> Color(0xFFFBBF24) // Amber / Solar
        VoiceState.ERROR -> Color(0xFFF87171) // Coral Red
        VoiceState.IDLE -> Color(0xFFA855F7) // Purple
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .testTag("cosmic_orb_container")
            .size(310.dp)
    ) {
        // Fluid Ripple Canvas rings reacting to voice amplitude
        Canvas(
            modifier = Modifier.size(310.dp)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = 110.dp.toPx()
            val maxExpansion = 42.dp.toPx() + (amplitude * 35.dp.toPx())

            if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING) {
                // Ring 1
                val r1 = baseRadius + (rippleProgress1 * maxExpansion)
                val alpha1 = (1f - rippleProgress1).coerceIn(0f, 0.6f) * (0.3f + amplitude * 0.7f)
                drawCircle(
                    color = statePrimaryColor.copy(alpha = alpha1),
                    radius = r1,
                    center = center,
                    style = Stroke(width = (2.5f * (1f - rippleProgress1)).dp.toPx())
                )

                // Ring 2
                val r2 = baseRadius + (rippleProgress2 * maxExpansion)
                val alpha2 = (1f - rippleProgress2).coerceIn(0f, 0.6f) * (0.3f + amplitude * 0.7f)
                drawCircle(
                    color = Color(0xFFF472B6).copy(alpha = alpha2),
                    radius = r2,
                    center = center,
                    style = Stroke(width = (2.5f * (1f - rippleProgress2)).dp.toPx())
                )
            }
        }

        // Outer radiant aura / bloom glow
        Box(
            modifier = Modifier
                .size(280.dp)
                .scale(dynamicScale * 1.06f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            statePrimaryColor.copy(alpha = 0.35f),
                            Color(0x227C3AED),
                            Color(0x00000000)
                        )
                    )
                )
        )

        // Cosmic glass sphere with swirling galaxy texture
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(240.dp)
                .scale(dynamicScale)
                .shadow(
                    elevation = 24.dp,
                    shape = CircleShape,
                    ambientColor = statePrimaryColor,
                    spotColor = Color(0xFFA855F7)
                )
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            Color(0xFFE879F9),
                            statePrimaryColor,
                            Color(0xFF818CF8),
                            Color(0xFFE879F9)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            // Rotating cosmic portal nebula
            Image(
                painter = painterResource(id = R.drawable.cosmic_portal_ring),
                contentDescription = "Cosmic Portal",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(240.dp)
                    .rotate(rotation)
            )

            // Dark center vignette overlay to make wave bars pop
            Box(
                modifier = Modifier
                    .size(170.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xDD090514),
                                Color(0x99180C33),
                                Color(0x00000000)
                            )
                        )
                    )
            )

            // Voice Waveform Bars inside the core
            WaveformBars(
                voiceState = voiceState,
                amplitude = amplitude
            )
        }
    }
}

@Composable
private fun WaveformBars(
    voiceState: VoiceState,
    amplitude: Float
) {
    val barMultipliers = listOf(
        0.35f, 0.55f, 0.8f, 1.15f, 1.45f, 1.7f, 1.45f, 1.15f, 0.8f, 0.55f, 0.35f
    )

    val infiniteTransition = rememberInfiniteTransition(label = "wave_harmonics")
    val harmonicPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "harmonic_phase"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .testTag("waveform_bars")
            .padding(horizontal = 8.dp)
    ) {
        barMultipliers.forEachIndexed { index, multiplier ->
            // Natural dynamic height variation based on amplitude & state
            val baseHeight = when (voiceState) {
                VoiceState.LISTENING -> {
                    val sineWobble = kotlin.math.sin(harmonicPhase + (index * 0.7f)).toFloat()
                    val dynamic = 8f + (amplitude * 42f * multiplier) + (sineWobble * 4f)
                    dynamic.coerceIn(8f, 64f).dp
                }
                VoiceState.SPEAKING -> {
                    val sineWobble = kotlin.math.abs(kotlin.math.sin(harmonicPhase * 1.5f + (index * 0.9f))).toFloat()
                    val dynamic = 12f + (amplitude * 56f * multiplier) + (sineWobble * 10f)
                    dynamic.coerceIn(10f, 72f).dp
                }
                VoiceState.PROCESSING -> {
                    val wave = kotlin.math.sin(harmonicPhase * 2f + (index * 0.5f)).toFloat()
                    val h = 14f + (kotlin.math.abs(wave) * 22f * multiplier * 0.7f)
                    h.coerceIn(10f, 40f).dp
                }
                else -> {
                    val gentle = kotlin.math.sin(harmonicPhase + (index * 0.4f)).toFloat()
                    (10f + (multiplier * 12f) + (gentle * 2f)).coerceIn(8f, 32f).dp
                }
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(4.5.dp)
                    .height(baseHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        Brush.verticalGradient(
                            colors = if (index in 4..6) {
                                listOf(
                                    Color(0xFFFFFFFF),
                                    Color(0xFFF5D0FE),
                                    Color(0xFFD946EF)
                                )
                            } else {
                                listOf(
                                    Color(0xFFE9D5FF),
                                    Color(0xFFA855F7),
                                    Color(0xFF7E22CE)
                                )
                            }
                        )
                    )
            )
        }
    }
}
