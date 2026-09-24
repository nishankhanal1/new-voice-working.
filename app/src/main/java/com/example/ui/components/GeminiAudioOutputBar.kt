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
 * listen to, pause, replay, or test Gemini's spoken voice response.
 */
@Composable
fun GeminiAudioOutputBar(
    isPlaying: Boolean,
    selectedVoice: String,
    hasResponse: Boolean = false,
    playbackSpeed: Float = 1.0f,
    onSpeedChange: (Float) -> Unit = {},
    onTogglePlay: () -> Unit,
    onTestVoice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_bars")

    // Pulsing scale for speaker icon when audio is actively playing
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isPlaying) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Animated sound wave bars
    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = if (isPlaying) 18f else 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )

    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 9f,
        targetValue = if (isPlaying) 24f else 9f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, delayMillis = 100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )

    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 5f,
        targetValue = if (isPlaying) 16f else 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, delayMillis = 150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isPlaying) Color(0xFFA855F7) else Color(0xFF2E1C52),
        label = "border_color"
    )

    val buttonBgColor by animateColorAsState(
        targetValue = if (isPlaying) Color(0xFFE11D48) else Color(0xFF7C3AED),
        label = "btn_bg"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF140C2B),
                        Color(0xFF0F0822)
                    )
                )
            )
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag("audio_output_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Left: Speaker icon & Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) Color(0xFF7E22CE).copy(alpha = 0.35f)
                            else Color(0xFF221242)
                        )
                        .border(
                            1.dp,
                            if (isPlaying) Color(0xFFA855F7) else Color(0xFF432578),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Audio Output Speaker",
                        tint = if (isPlaying) Color(0xFF38BDF8) else Color(0xFFC084FC),
                        modifier = Modifier
                            .size(19.dp)
                            .scale(pulseScale)
                    )
                }

                Spacer(modifier = Modifier.width(11.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Gemini Real Voice",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2B1650))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = selectedVoice,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE9D5FF)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (isPlaying) "नेपालीमा बोल्दैछ..." else if (hasResponse) "जवाफ सुन्न ट्याप गर्नुहोस्" else "आवाज आउटपुट तयार छ",
                        fontSize = 11.sp,
                        color = if (isPlaying) Color(0xFF38BDF8) else Color(0xFFA78BFA)
                    )
                }
            }

            // Right: Actions Row (Equalizer + Test Voice + Play/Stop)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mini 3-bar animated equalizer when playing
                if (isPlaying) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .height(24.dp)
                            .padding(end = 4.dp)
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

                // Playback Speed Toggle (0.8x -> 1.0x -> 1.25x)
                val speedLabel = when {
                    playbackSpeed <= 0.85f -> "०.८x"
                    playbackSpeed >= 1.2f -> "१.२x"
                    else -> "१.०x"
                }
                val nextSpeed = when {
                    playbackSpeed <= 0.85f -> 1.0f
                    playbackSpeed in 0.86f..1.1f -> 1.25f
                    else -> 0.8f
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF22133D))
                        .border(1.dp, Color(0xFF4C277E), RoundedCornerShape(14.dp))
                        .clickable { onSpeedChange(nextSpeed) }
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                        .testTag("playback_speed_button")
                ) {
                    Text(
                        text = speedLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }

                // Test Voice Output Button (Quick test prompt)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF22133D))
                        .border(1.dp, Color(0xFF4C277E), RoundedCornerShape(14.dp))
                        .clickable(onClick = onTestVoice)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("test_voice_output_button")
                ) {
                    Text(
                        text = "परीक्षण",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFD8B4FE)
                    )
                }

                // Interactive Audio Output Play/Stop Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(buttonBgColor)
                        .clickable(onClick = onTogglePlay)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
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
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isPlaying) "रोक्नुहोस्" else "सुन्नुहोस्",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
