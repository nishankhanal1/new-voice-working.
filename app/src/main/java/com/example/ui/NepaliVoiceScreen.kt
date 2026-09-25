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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ui.NepaliPersona
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
    val isContinuousMode by viewModel.isContinuousMode.collectAsState()
    val isSpeechDetected by viewModel.isSpeechDetected.collectAsState()
    val currentPersona by viewModel.currentPersona.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val silenceTimeoutMs by viewModel.silenceTimeoutMs.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showPlusDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }
    var summaryText by remember { mutableStateOf<String?>(null) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Keep screen awake during continuous long-term voice conversations
    val currentView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(isContinuousMode, voiceState) {
        val keepOn = isContinuousMode || voiceState != VoiceState.IDLE
        currentView.keepScreenOn = keepOn
        onDispose {
            currentView.keepScreenOn = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // -------------------------------------------------------------------------------------
            // TOP ACTION BAR (Settings, Continuous Mode Switch, Get Plus ✨, History)
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

                // Center-Left: "अविरल (Auto Listen / Long-Term)" Toggle Chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            if (isContinuousMode) Color(0xFF065F46).copy(alpha = 0.45f)
                            else Color(0xFF1C1236)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isContinuousMode) Color(0xFF10B981) else Color(0xFF3B2568),
                            shape = RoundedCornerShape(percent = 50)
                        )
                        .clickable {
                            if (hasAudioPermission) {
                                viewModel.toggleContinuousMode()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                        .testTag("continuous_mode_toggle_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isContinuousMode) Color(0xFF10B981) else Color(0xFF6B7280))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isContinuousMode) "अविरल ON" else "अविरल कुराकानी",
                        color = if (isContinuousMode) Color(0xFF6EE7B7) else Color(0xFFC084FC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Center-Right: "Get Plus ✨" Pill Button
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
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .testTag("get_plus_button")
                ) {
                    Text(
                        text = "Plus",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFF0ABFC),
                        modifier = Modifier.size(13.dp)
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

            Spacer(modifier = Modifier.height(10.dp))

            // -------------------------------------------------------------------------------------
            // 1-TAP PERSONA SELECTOR ROW (Buddy, Formal, Fast, Storyteller)
            // -------------------------------------------------------------------------------------
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                NepaliPersona.values().forEach { persona ->
                    val isSelected = currentPersona == persona
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(
                                if (isSelected) Color(0xFF3B1869)
                                else Color(0xFF140D29)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFF38BDF8) else Color(0x334C1D95),
                                shape = RoundedCornerShape(percent = 50)
                            )
                            .clickable { viewModel.setPersona(persona) }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            text = persona.badge,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF9CA3AF)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = persona.titleNepali,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color(0xFFC084FC)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                // Status pill (Listening, Thinking, Speaking, Continuous)
                val statusText = when (voiceState) {
                    VoiceState.LISTENING -> {
                        if (isSpeechDetected) "आवाज सुन्दैछ… (Voice detected…)"
                        else if (isContinuousMode) "अविरल सुन्दैछ… (Continuous listening…)"
                        else "सुन्दैछ… बोल्नुहोस् (Listening…)"
                    }
                    VoiceState.PROCESSING -> "सोच्दैछ… (Thinking…)"
                    VoiceState.SPEAKING -> "नेपालीमा बोल्दैछ… (रोक्न बोल्नुहोस् वा माइक थिच्नुहोस्)"
                    VoiceState.ERROR -> "त्रुटि (Attention)"
                    VoiceState.IDLE -> {
                        if (isContinuousMode) "अविरल मोड सक्रिय (Continuous Active)"
                        else "बोल्नको लागि माइक थिच्नुहोस् (Tap mic to talk)"
                    }
                }

                val statusBadgeColor = when (voiceState) {
                    VoiceState.LISTENING -> if (isSpeechDetected) Color(0xFF38BDF8) else Color(0xFFEC4899)
                    VoiceState.PROCESSING -> Color(0xFFF59E0B)
                    VoiceState.SPEAKING -> Color(0xFF10B981)
                    VoiceState.ERROR -> Color(0xFFEF4444)
                    VoiceState.IDLE -> if (isContinuousMode) Color(0xFF10B981) else Color(0xFFA855F7)
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

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .heightIn(max = 170.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // User Question / Current utterance headline
                    Text(
                        text = currentPrompt,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (currentAiResponse.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))

                        // AI Response container (clean, sleek card)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF160D2E).copy(alpha = 0.85f))
                                .border(1.dp, Color(0xFF361E5E), RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = currentAiResponse,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color(0xFFE9D5FF),
                                textAlign = TextAlign.Center,
                                lineHeight = 23.sp,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Subtle, clean copy action button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF221345))
                                    .border(1.dp, Color(0xFF452277), RoundedCornerShape(12.dp))
                                    .clickable { viewModel.copyResponse(context) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag("copy_response_quick_button")
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy Output",
                                    tint = Color(0xFFD8B4FE),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "कपी",
                                    color = Color(0xFFD8B4FE),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Error notice if any
                    if (!errorMessage.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            fontSize = 12.sp,
                            color = Color(0xFFFCA5A5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // -------------------------------------------------------------------------------------
            // REAL-WORLD NEPALI TOPIC PROMPTS CAROUSEL (One-tap inspiration)
            // -------------------------------------------------------------------------------------
            QuickNepaliTopicsRow(
                onSelectTopic = { viewModel.askQuickTopic(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // -------------------------------------------------------------------------------------
            // DEDICATED GEMINI AUDIO OUTPUT PLAYER (Hear AI Voice Response + Speed Control)
            // -------------------------------------------------------------------------------------
            GeminiAudioOutputBar(
                isPlaying = isAudioPlaying,
                selectedVoice = selectedVoice,
                hasResponse = currentAiResponse.isNotBlank(),
                playbackSpeed = playbackSpeed,
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                onTogglePlay = { viewModel.togglePlayLatestAudio() },
                onTestVoice = { viewModel.testGeminiVoice() },
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

                // When actively listening, show instant "बोलिसकें ✓" pill button so user can conclude long talk immediately
                if (voiceState == VoiceState.LISTENING) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color(0xFF2E1065).copy(alpha = 0.95f))
                            .border(1.dp, Color(0xFFA855F7), RoundedCornerShape(percent = 50))
                            .clickable { viewModel.onMicToggled() }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("done_speaking_button")
                    ) {
                        Icon(
                            Icons.Default.Done,
                            contentDescription = "Done Speaking",
                            tint = Color(0xFFE9D5FF),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "बोलिसकें (Done)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF3E8FF)
                        )
                    }
                }

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
            currentPersona = currentPersona,
            silenceTimeoutMs = silenceTimeoutMs,
            onVoiceSelected = { viewModel.setVoice(it) },
            onPersonaSelected = { viewModel.setPersona(it) },
            onSilenceTimeoutChanged = { viewModel.setSilenceTimeout(it) },
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
            onCopy = { viewModel.copyResponse(context, it.text) },
            onToggleBookmark = { viewModel.toggleBookmark(it) },
            onExportTranscript = {
                val transcript = viewModel.exportTranscript()
                val sendIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_TEXT, transcript)
                    type = "text/plain"
                }
                context.startActivity(android.content.Intent.createChooser(sendIntent, "नेपाली कुराकानी सेयर गर्नुहोस्"))
            },
            onGenerateSummary = {
                viewModel.generateSessionSummary { summary ->
                    summaryText = summary
                }
            },
            onClearHistory = { viewModel.clearHistory() },
            onDismiss = { showHistory = false }
        )
    }

    if (summaryText != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { summaryText = null }
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF140D2B),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "कुराकानी सारांश (AI Summary)",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        IconButton(onClick = { summaryText = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFC084FC))
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = summaryText ?: "",
                        fontSize = 14.sp,
                        color = Color(0xFFE9D5FF),
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.Button(
                        onClick = {
                            viewModel.copyResponse(context, summaryText)
                            summaryText = null
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("सारांश कपी गर्नुहोस् (Copy Summary)", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showTextInput) {
        TextInputDialog(
            onSend = { viewModel.sendTextMessage(it) },
            onDismiss = { showTextInput = false }
        )
    }
}

@Composable
fun QuickNepaliTopicsRow(
    onSelectTopic: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val topics = remember {
        listOf(
            "🌦️ काठमाडौंको मौसम" to "काठमाडौंको आजको मौसम र तापक्रम कस्तो छ?",
            "🍲 दाल-भात र मोमो" to "नेपाली दालभात र मिठो मोमो बनाउने सही तरिका बताउनुहोस्।",
            "🏔️ अन्नपूर्ण ट्रेकिङ" to "अन्नपूर्ण बेसक्याम्प घुम्न जान उत्तम मौसम र तयारी के हो?",
            "📜 मिठो नेपाली उखान" to "एउटा गम्भीर अर्थ भएको चर्चित नेपाली उखान र त्यसको अर्थ भन्नुहोस्।",
            "🇳🇵 नेपालको इतिहास" to "नेपाल एकीकरण र पृथ्वीनारायण शाहको इतिहासबारे छोटकरीमा भन्नुहोस्।",
            "🧘 ध्यान र शान्ति" to "दिनभरको तनाव शान्त पार्ने र ध्यान गर्ने सरल उपाय के हो?"
        )
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(topics) { (label, prompt) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0xFF1B1138))
                    .border(1.dp, Color(0xFF3E2368), RoundedCornerShape(percent = 50))
                    .clickable { onSelectTopic(prompt) }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE9D5FF)
                )
            }
        }
    }
}
