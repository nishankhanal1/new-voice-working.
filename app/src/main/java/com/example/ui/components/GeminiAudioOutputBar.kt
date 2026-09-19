package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dedicated Audio Output control bar where the user can directly
 * listen to, pause, or replay Gemini's spoken voice response.
 */
@Composable
fun GeminiAudioOutputBar(
    isPlaying: Boolean,
    selectedVoice: String,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_bars")

    // Pulsing scale for speaker icon when audio is actively playing
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isPlaying) 1.18f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Animated sound wave bars
    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = if (isPlaying) 20f else 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )

    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = if (isPlaying) 26f else 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, delayMillis = 100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )

    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 5f,
        targetValue = if (isPlaying) 18f else 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, delayMillis = 150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isPlaying) Color(0xFFA855F7) else Color(0xFF382363),
        label = "border_color"
    )

    val buttonBgColor by animateColorAsState(
        targetValue = if (isPlaying) Color(0xFFDC2626) else Color(0xFF9333EA),
        label = "btn_bg"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF190F33).copy(alpha = 0.95f),
                        Color(0xFF140A28).copy(alpha = 0.95f)
                    )
                )
            )
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("audio_output_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Left: Speaker icon & Equalizer
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) Color(0xFF7E22CE).copy(alpha = 0.35f)
                            else Color(0xFF2E1A54)
                        )
                        .border(
                            1.dp,
                            if (isPlaying) Color(0xFFA855F7) else Color(0xFF4C2A85),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Audio Output Speaker",
                        tint = if (isPlaying) Color(0xFF38BDF8) else Color(0xFFC084FC),
                        modifier = Modifier
                            .size(20.dp)
                            .scale(pulseScale)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Gemini Voice Output",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2D1854))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = selectedVoice,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE9D5FF)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (isPlaying) "आवाज बोल्दैछ... (Speaking now)" else "जवाफ सुन्नुहोस् (Tap to hear)",
                        fontSize = 11.sp,
                        color = if (isPlaying) Color(0xFF38BDF8) else Color(0xFFDDD6FE)
                    )
                }
            }

            // Right: Mini Equalizer + Play/Stop Action Button
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini 3-bar animated equalizer when playing
                if (isPlaying) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .padding(end = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(bar1Height.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF38BDF8))
                        )
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(bar2Height.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFFA855F7))
                        )
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(bar3Height.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFFF472B6))
                        )
                    }
                }

                // Interactive Audio Output Play/Stop Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(buttonBgColor)
                        .clickable(onClick = onTogglePlay)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("audio_output_play_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Stop Audio" else "Play Audio",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isPlaying) "रोक्नुहोस्" else "सुन्नुहोस्",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
