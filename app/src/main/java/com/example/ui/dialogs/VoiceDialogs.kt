package com.example.ui.dialogs

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.ChatItem
import com.example.ui.NepaliPersona

// -------------------------------------------------------------------------------------------------
// 1. SETTINGS DIALOG
// -------------------------------------------------------------------------------------------------
@Composable
fun SettingsDialog(
    currentVoice: String,
    currentApiKey: String,
    currentPersona: NepaliPersona = NepaliPersona.BUDDY,
    onVoiceSelected: (String) -> Unit,
    onPersonaSelected: (NepaliPersona) -> Unit = {},
    onApiKeySaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKeyInput by remember { mutableStateOf(currentApiKey) }
    val availableVoices = listOf(
        Pair("Puck", "Energetic, Natural Male/Neutral"),
        Pair("Kore", "Warm, Melodic Female"),
        Pair("Aoede", "Polite, Expressive Female"),
        Pair("Fenrir", "Deep, Authoritative Male"),
        Pair("Charon", "Calm, Steady Male")
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF140D2B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4C1D95)),
            modifier = Modifier
                .testTag("settings_dialog")
                .fillMaxWidth()
                .padding(16.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "सेटिङहरू (Settings)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFC084FC))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Conversational Persona selection
                Text(
                    text = "वार्तालाप शैली (Conversational Persona):",
                    fontSize = 13.sp,
                    color = Color(0xFFD8B4FE),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "नेपाली अभिव्यक्तिको शैली रोज्नुहोस्:",
                    fontSize = 10.sp,
                    color = Color(0xFF9CA3AF)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NepaliPersona.values().forEach { persona ->
                        val isSelected = currentPersona == persona
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFF3B1869) else Color(0xFF1C143B))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0x336D28D9),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onPersonaSelected(persona) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color(0xFF0284C7) else Color(0xFF2E1065))
                            ) {
                                Text(
                                    text = persona.badge,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = persona.titleNepali,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = persona.subtitleNepali,
                                    fontSize = 11.sp,
                                    color = Color(0xFFC084FC)
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Voice selection
                Text(
                    text = "Gemini Real Voice:",
                    fontSize = 13.sp,
                    color = Color(0xFFD8B4FE),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableVoices.forEach { (voiceKey, desc) ->
                        val isSelected = currentVoice == voiceKey
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFF3B1869) else Color(0xFF1C143B))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFFA855F7) else Color(0x336D28D9),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onVoiceSelected(voiceKey) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = if (isSelected) Color(0xFFF472B6) else Color(0xFF9333EA),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = voiceKey,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    color = Color(0xFFC084FC)
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Gemini API Key override
                Text(
                    text = "Gemini API Key (वैकल्पिक / Optional):",
                    fontSize = 13.sp,
                    color = Color(0xFFD8B4FE),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "AI Studio automatically injects your free API key, or you can paste your custom key here.",
                    fontSize = 10.sp,
                    color = Color(0xFF9CA3AF)
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    placeholder = { Text("Paste AIzaSy... (leave blank for default)", fontSize = 12.sp, color = Color(0x77FFFFFF)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFFA855F7)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFA855F7),
                        unfocusedBorderColor = Color(0xFF4C1D95),
                        focusedContainerColor = Color(0xFF0F0A21),
                        unfocusedContainerColor = Color(0xFF0F0A21)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        onApiKeySaved(apiKeyInput)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C3AED)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("बचत गर्नुहोस् (Save Settings)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 2. GET PLUS DIALOG
// -------------------------------------------------------------------------------------------------
@Composable
fun GetPlusDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF130C29),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFA855F7)),
            modifier = Modifier
                .testTag("get_plus_dialog")
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFE879F9), Color(0xFF7C3AED))
                            )
                        )
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Nepali Voice Plus ✨",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )

                Text(
                    text = "Ultra Low Latency AI Voice Engine",
                    fontSize = 12.sp,
                    color = Color(0xFFD8B4FE)
                )

                Spacer(modifier = Modifier.height(18.dp))

                val features = listOf(
                    "⚡ Instant < 350ms response latency",
                    "🎙️ Gemini 2.5 Flash Native High-Fidelity Voice",
                    "🇳🇵 Native, authentic Nepali accent & grammar",
                    "♾️ Unlimited continuous voice conversations",
                    "🔒 Privacy-first on-device audio streaming"
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1B1238))
                        .padding(14.dp)
                ) {
                    features.forEach { item ->
                        Text(text = item, color = Color(0xFFF3E8FF), fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF9333EA)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("सुरु गर्नुहोस् (Active & Included)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 3. CONVERSATION HISTORY SHEET
// -------------------------------------------------------------------------------------------------
@Composable
fun ChatHistoryDialog(
    history: List<ChatItem>,
    onReplay: (ChatItem) -> Unit,
    onCopy: (ChatItem) -> Unit = {},
    onToggleBookmark: (String) -> Unit = {},
    onExportTranscript: () -> Unit = {},
    onGenerateSummary: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF120A28),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B1869)),
            modifier = Modifier
                .testTag("chat_history_dialog")
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .heightIn(max = 540.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "कुराकानी इतिहास (Conversation)",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${history.size} संवाद रेकर्ड (Memory & Bookmarks)",
                            fontSize = 11.sp,
                            color = Color(0xFFC084FC)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFC084FC))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Bar: Summary & Export Transcript
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // AI Summary button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2E1065))
                            .border(1.dp, Color(0xFF7C3AED), RoundedCornerShape(8.dp))
                            .clickable { onGenerateSummary() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "सारांश (Summary)",
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Export Transcript button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E143B))
                            .border(1.dp, Color(0xFF4C1D95), RoundedCornerShape(8.dp))
                            .clickable { onExportTranscript() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            tint = Color(0xFFD8B4FE),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ट्रान्सक्रिप्ट (Export)",
                            fontSize = 11.sp,
                            color = Color(0xFFD8B4FE),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (history.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    ) {
                        Text("कुनै कुराकानी भेटिएन (No history yet)", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(history, key = { it.id }) { item ->
                            val isUser = item.sender == "User"
                            Row(
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth(0.95f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isUser) Color(0xFF3B1869) else Color(0xFF1F1440)
                                        )
                                        .border(
                                            1.dp,
                                            if (item.isBookmarked) Color(0xFFFBBF24)
                                            else if (isUser) Color(0xFFA855F7) else Color(0x337C3AED),
                                            RoundedCornerShape(16.dp)
                                        )
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (isUser) "तपाईं (You)" else "Gemini AI 🇳🇵",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isUser) Color(0xFFF472B6) else Color(0xFF38BDF8)
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.timestamp,
                                                fontSize = 10.sp,
                                                color = Color(0xFF9CA3AF)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                imageVector = if (item.isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                                contentDescription = "Bookmark",
                                                tint = if (item.isBookmarked) Color(0xFFFBBF24) else Color(0xFF6B7280),
                                                modifier = Modifier
                                                    .size(17.dp)
                                                    .clickable { onToggleBookmark(item.id) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.text,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Action buttons for message (Play, Share, Copy)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (!isUser && (item.audioBytes != null || item.text.isNotBlank())) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(0xFF2E1065))
                                                    .clickable { onReplay(item) }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.PlayArrow,
                                                    contentDescription = "Replay voice",
                                                    tint = Color(0xFFE879F9),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "सुन्नुहोस्",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFFE879F9),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }

                                        // Copy response text
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1E143B))
                                                .clickable { onCopy(item) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "Copy",
                                                tint = Color(0xFFD8B4FE),
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "कपी",
                                                fontSize = 11.sp,
                                                color = Color(0xFFD8B4FE)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bottom: Clear History Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = {
                                onClearHistory()
                                onDismiss()
                            }
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Clear History",
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("नयाँ कुराकानी सुरु (Clear)", color = Color(0xFFF87171), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 4. TEXT INPUT DIALOG
// -------------------------------------------------------------------------------------------------
@Composable
fun TextInputDialog(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    val quickPrompts = listOf(
        "नमस्ते! तपाईं कस्तो हुनुहुन्छ?",
        "काठमाडौँको बारेमा केही भन्नुहोस्।",
        "आजको मौसम कस्तो छ?",
        "नेपाली खानाका परिकार के के हुन्?"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF130B29),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4C1D95)),
            modifier = Modifier
                .testTag("text_input_dialog")
                .fillMaxWidth()
                .padding(16.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "नेपालीमा लेख्नुहोस् (Type Message)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFC084FC))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("नेपाली वा अंग्रेजीमा केही सोध्नुहोस्...", color = Color(0x77FFFFFF), fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFA855F7),
                        unfocusedBorderColor = Color(0xFF3B1869),
                        focusedContainerColor = Color(0xFF0F0A21),
                        unfocusedContainerColor = Color(0xFF0F0A21)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text("सुझावहरू (Suggestions):", fontSize = 11.sp, color = Color(0xFFD8B4FE))
                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    quickPrompts.forEach { prompt ->
                        Text(
                            text = "• $prompt",
                            fontSize = 12.sp,
                            color = Color(0xFFC084FC),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { textInput = prompt }
                                .padding(vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onSend(textInput.trim())
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("पठाउनुहोस् (Send)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
