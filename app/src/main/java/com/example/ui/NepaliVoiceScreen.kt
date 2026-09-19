package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.components.CosmicOrbView
import com.example.ui.components.GeminiAudioOutputBar
import com.example.ui.components.MicButtonWithRipples
import com.example.ui.dialogs.ChatHistoryDialog
import com.example.ui.dialogs.GetPlusDialog
import com.example.ui.dialogs.SettingsDialog
import com.example.ui.dialogs.TextInputDialog

@Composable
fun NepaliVoiceScreen(
    viewModel: NepaliVoiceViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val voiceState by viewModel.voiceState.collectAsState()
    val currentPrompt by viewModel.currentPrompt.collectAsState()
    val currentAiResponse by viewModel.currentAiResponse.collectAsState()
    val amplitude by viewModel.audioAmplitude.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val history by viewModel.history.collectAsState()
    val isAudioPlaying by viewModel.isAudioPlaying.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showPlusDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            viewModel.onMicToggled()
        }
    }

    // Deep cosmic gradient background
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F0826),
                        Color(0xFF080415),
                        Color(0xFF03010A)
                    )
                )
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // -------------------------------------------------------------------------------------
            // TOP ACTION BAR (Settings, Get Plus ✨, History)
            // -------------------------------------------------------------------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // Left: Settings Button
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E143B).copy(alpha = 0.85f))
                        .border(1.dp, Color(0xFF3B2568), CircleShape)
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFFC084FC),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Center: "Get Plus ✨" Pill Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF2E1254), Color(0xFF1D0F38))
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                listOf(Color(0xFFE879F9), Color(0xFFA855F7), Color(0xFF6366F1))
                            ),
                            shape = RoundedCornerShape(percent = 50)
                        )
                        .clickable { showPlusDialog = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                        .testTag("get_plus_button")
                ) {
                    Text(
                        text = "Get Plus",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFF0ABFC),
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Right: History Button
                IconButton(
                    onClick = { showHistory = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E143B).copy(alpha = 0.85f))
                        .border(1.dp, Color(0xFF3B2568), CircleShape)
                        .testTag("history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Conversation History",
                        tint = Color(0xFFC084FC),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // -------------------------------------------------------------------------------------
            // CENTER COSMIC ORB WITH AUDIO WAVEFORM
            // -------------------------------------------------------------------------------------
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxWidth()
            ) {
                CosmicOrbView(
                    voiceState = voiceState,
                    amplitude = amplitude
                )
            }

            // -------------------------------------------------------------------------------------
            // CONVERSATION DISPLAY TEXT (User Utterance + AI Response)
            // -------------------------------------------------------------------------------------
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                // Status pill (Listening, Thinking, Speaking)
                val statusText = when (voiceState) {
                    VoiceState.LISTENING -> "सुन्दैछ… (Listening…)"
                    VoiceState.PROCESSING -> "सोच्दैछ… (Thinking…)"
                    VoiceState.SPEAKING -> "नेपालीमा बोल्दैछ… (Speaking…)"
                    VoiceState.ERROR -> "त्रुटि (Attention)"
                    VoiceState.IDLE -> "बोल्नको लागि माइक थिच्नुहोस् (Tap mic to talk)"
                }

                val statusBadgeColor = when (voiceState) {
                    VoiceState.LISTENING -> Color(0xFFEC4899)
                    VoiceState.PROCESSING -> Color(0xFFF59E0B)
                    VoiceState.SPEAKING -> Color(0xFF10B981)
                    VoiceState.ERROR -> Color(0xFFEF4444)
                    VoiceState.IDLE -> Color(0xFFA855F7)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color(0xFF1A1136))
                        .border(1.dp, statusBadgeColor.copy(alpha = 0.45f), RoundedCornerShape(percent = 50))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusBadgeColor)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE9D5FF)
                    )
                    if (latencyMs != null && voiceState != VoiceState.LISTENING) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• ${latencyMs}ms",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // User Question / Current utterance headline
                Text(
                    text = currentPrompt,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 25.sp,
                    modifier = Modifier.fillMaxWidth(0.92f)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // AI Response text (subtle lilac accent)
                Text(
                    text = currentAiResponse,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFFC084FC),
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth(0.92f)
                )

                // Error notice if any
                if (!errorMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        fontSize = 12.sp,
                        color = Color(0xFFFCA5A5),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // -------------------------------------------------------------------------------------
            // DEDICATED GEMINI AUDIO OUTPUT PLAYER (Hear AI Voice Response)
            // -------------------------------------------------------------------------------------
            GeminiAudioOutputBar(
                isPlaying = isAudioPlaying,
                selectedVoice = selectedVoice,
                onTogglePlay = { viewModel.togglePlayLatestAudio() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // -------------------------------------------------------------------------------------
            // BOTTOM MIC BUTTON WITH RIPPLES & ACTION BAR
            // -------------------------------------------------------------------------------------
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
            ) {
                // Main Mic Button with concentric dotted ripple rings
                MicButtonWithRipples(
                    voiceState = voiceState,
                    amplitude = amplitude,
                    onClick = {
                        if (hasAudioPermission) {
                            viewModel.onMicToggled()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )

                // Bottom actions row: Left = Chat input, Right = Stop/Close
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .align(Alignment.Center)
                ) {
                    // Left: Chat / Text button
                    IconButton(
                        onClick = { showTextInput = true },
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1B1238))
                            .border(1.dp, Color(0xFF382363), CircleShape)
                            .testTag("chat_input_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Text Chat",
                            tint = Color(0xFFC084FC),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Right: Stop / Close (X) button
                    IconButton(
                        onClick = { viewModel.cancelCurrentOperation() },
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1B1238))
                            .border(1.dp, Color(0xFF382363), CircleShape)
                            .testTag("stop_cancel_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop",
                            tint = Color(0xFFC084FC),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DIALOGS
    // ---------------------------------------------------------------------------------------------
    if (showSettings) {
        SettingsDialog(
            currentVoice = selectedVoice,
            currentApiKey = apiKey,
            onVoiceSelected = { viewModel.setVoice(it) },
            onApiKeySaved = { viewModel.setApiKey(it) },
            onDismiss = { showSettings = false }
        )
    }

    if (showPlusDialog) {
        GetPlusDialog(onDismiss = { showPlusDialog = false })
    }

    if (showHistory) {
        ChatHistoryDialog(
            history = history,
            onReplay = { viewModel.replayAudio(it) },
            onDismiss = { showHistory = false }
        )
    }

    if (showTextInput) {
        TextInputDialog(
            onSend = { viewModel.sendTextMessage(it) },
            onDismiss = { showTextInput = false }
        )
    }
}
