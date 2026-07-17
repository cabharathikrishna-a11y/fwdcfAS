package com.example.ui.components

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.api.*
import com.example.data.LocalHistoryVault
import com.example.data.LocalShieldsVault
import com.example.ui.AppViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArenaScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val sduiPrefs = com.example.api.RemoteConfigManager.sduiPreferences.collectAsState().value
    if (!sduiPrefs.isArenaEnabled) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F11)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Leaderboard Maintenance",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Leaderboard Under Maintenance",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "The CA Inter Arena is currently down for scheduled synchronization optimizations. Please check back shortly!",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
        return
    }

    val context = LocalContext.current
    val email = viewModel.getActiveUserEmail()

    // Sync shields and start leaderboard listeners
    LaunchedEffect(email) {
        if (email.isNotBlank()) {
            ArenaLeaderboardEngine.startListening(context, email)
            StreakShieldManager.fetchAndSyncShields(context, email)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ArenaLeaderboardEngine.stopListening()
        }
    }

    val leaderboard by ArenaLeaderboardEngine.leaderboardFlow.collectAsState()
    val historyRecords by viewModel.allHistoryVault.collectAsState()
    val localShields by StreakShieldManager.getLocalShieldsFlow(context).collectAsState(initial = emptyList())

    val activeShields = remember(localShields) { localShields.filter { !it.is_consumed } }

    val peerUiCards by viewModel.peerUiCards.collectAsState()
    val isTimerRunning by com.example.util.FocusTimerManager.isTimerRunning.collectAsState()
    val isStopwatchActive by com.example.util.FocusTimerManager.isStopwatchActive.collectAsState()
    val isFocusPhase by com.example.util.FocusTimerManager.isFocusPhase.collectAsState()
    val isPaused by com.example.util.FocusTimerManager.isPaused.collectAsState()
    val accumulatedSessionTimeMs by com.example.util.FocusTimerManager.accumulatedSessionTimeMs.collectAsState()
    val currentUsername by viewModel.currentUsername.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val userNickname by viewModel.userNickname.collectAsState()
    val userEmail by viewModel.userEmail.collectAsState()
    val userEmoji by viewModel.userEmoji.collectAsState()

    var masteryPeriod by remember { mutableStateOf("WEEKLY") } // "TODAY", "WEEKLY", "MONTHLY"
    var activeTabSelection by remember { mutableStateOf(0) } // 0 = Arena, 1 = Syllabus Tree

    var showShieldsBottomSheet by remember { mutableStateOf(false) }
    var showGiftConfirmDialog by remember { mutableStateOf(false) }
    var giftingPeer by remember { mutableStateOf<ArenaRankModel?>(null) }
    var isGiftingInProgress by remember { mutableStateOf(false) }
    var giftErrorMsg by remember { mutableStateOf<String?>(null) }
    var giftSuccessMsg by remember { mutableStateOf<String?>(null) }

    val myEmail = remember(userEmail, currentUsername) {
        val emailVal = userEmail ?: ""
        if (emailVal.isNotEmpty()) {
            emailVal
        } else if (currentUsername?.contains("@") == true) {
            currentUsername ?: ""
        } else {
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getString("user_email_$currentUsername", "") ?: ""
        }
    }

    val myDisplayName = remember(userName, userNickname, currentUsername, myEmail) {
        val base = if (!userNickname.isNullOrEmpty()) userNickname else if (!userName.isNullOrEmpty()) userName else currentUsername ?: ""
        if (base.isEmpty() || base == "Anonymous") {
            if (myEmail.isNotEmpty()) myEmail.substringBefore("@") else "Anonymous"
        } else {
            base
        }
    }

    val myTodayFocusMs = remember(peerUiCards, isTimerRunning, isStopwatchActive, isFocusPhase, isPaused, accumulatedSessionTimeMs) {
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val completedTodaySecs = com.example.util.FocusTimerManager.focusRecords.value.sumOf { com.example.util.FocusTimerManager.getOverlapSecondsForDate(it, todayStr) }
        val pendingSecs = com.example.util.FocusTimerManager.pendingFocusReview.value?.let { com.example.util.FocusTimerManager.getOverlapSecondsForDate(it, todayStr) } ?: 0
        val localCompletedMs = (completedTodaySecs + pendingSecs) * 1000L

        val myCard = peerUiCards.find { it.peerState.userId.lowercase().trim() == myEmail.lowercase().trim() }
        val maxDeviceTodayMs = myCard?.peerState?.devices?.values?.maxOfOrNull { it.todayFocusMs } ?: 0L
        val baseCompletedMs = maxOf(localCompletedMs, maxDeviceTodayMs)

        var localActiveMs = 0L
        val hasActiveSession = (isTimerRunning || isStopwatchActive || accumulatedSessionTimeMs > 0L) && com.example.util.FocusTimerManager.pendingFocusReview.value == null
        if (hasActiveSession) {
            localActiveMs = if (isFocusPhase && !isPaused) {
                val startMs = viewModel.sessionStartTimestamp.value
                if (startMs != null) {
                    com.example.util.FocusTimerManager.getActiveSessionOverlapSeconds(startMs, todayStr).toLong() * 1000L
                } else {
                    val currentChunkMs = com.example.util.FocusTimerManager.getCurrentChunkMs()
                    accumulatedSessionTimeMs + currentChunkMs
                }
            } else {
                accumulatedSessionTimeMs
            }
        }
        baseCompletedMs + localActiveMs
    }

    val filteredPeerUiCards = remember(peerUiCards, myEmail, currentUsername, myDisplayName) {
        val cleanMyEmail = myEmail.lowercase().trim()
        val cleanMyUsername = currentUsername?.lowercase()?.trim() ?: ""
        val cleanMyDisplayName = myDisplayName.lowercase().trim()
        
        fun normalize(str: String): String {
            return str.lowercase().replace(".", "").replace("_", "").replace("-", "").replace("@", "").trim()
        }
        
        val normalizedMyEmail = normalize(cleanMyEmail)
        val normalizedMyUsername = normalize(cleanMyUsername)
        val normalizedMyDisplayName = normalize(cleanMyDisplayName)

        peerUiCards.filter { card ->
            val peerIdClean = card.peerState.userId.lowercase().trim()
            val normalizedPeerId = normalize(peerIdClean)
            val normalizedPeerDisplayName = normalize(card.peerState.displayName)
            
            val isMe = (normalizedMyEmail.isNotEmpty() && normalizedPeerId == normalizedMyEmail) ||
                       (normalizedMyUsername.isNotEmpty() && normalizedPeerId == normalizedMyUsername) ||
                       (normalizedMyEmail.isNotEmpty() && normalizedPeerId.contains(normalizedMyEmail)) ||
                       (normalizedMyUsername.isNotEmpty() && normalizedPeerId.contains(normalizedMyUsername)) ||
                       (normalizedMyDisplayName.isNotEmpty() && normalizedPeerDisplayName == normalizedMyDisplayName) ||
                       peerIdClean == cleanMyEmail ||
                       peerIdClean == cleanMyUsername
            !isMe
        }
    }

    val todayRanks = remember(filteredPeerUiCards, myTodayFocusMs, myEmail, myDisplayName, userEmoji) {
        val list = mutableListOf<TodayRankModel>()
        
        // Add me
        list.add(
            TodayRankModel(
                email = myEmail,
                displayName = myDisplayName,
                todayFocusMs = myTodayFocusMs,
                isMe = true,
                customEmoji = userEmoji ?: "👤"
            )
        )
        
        // Add other unique peers
        filteredPeerUiCards.forEach { card ->
            list.add(
                TodayRankModel(
                    email = card.peerState.userId,
                    displayName = card.peerState.displayName,
                    todayFocusMs = card.rawElapsedMs,
                    isMe = false,
                    customEmoji = card.peerState.customEmoji ?: "👤"
                )
            )
        }
        
        // Sort descending by todayFocusMs
        list.distinctBy { it.email.lowercase().replace(".", "").replace("_", "").trim() }
            .sortedByDescending { it.todayFocusMs }
    }

    val masteryStats = remember(historyRecords, masteryPeriod) {
        getSyllabusMasteryForPeriod(historyRecords, masteryPeriod)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F11))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Elegant Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF16161A))
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Tab 1
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeTabSelection == 0) Color(0xFFFFB300).copy(alpha = 0.15f) else Color.Transparent)
                        .border(
                            1.dp, 
                            if (activeTabSelection == 0) Color(0xFFFFB300).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.05f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { activeTabSelection = 0 }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ACCOUNTABILITY ARENA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeTabSelection == 0) Color(0xFFFFB300) else Color.Gray,
                        letterSpacing = 1.sp
                    )
                }

                // Tab 2
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeTabSelection == 1) Color(0xFF00C853).copy(alpha = 0.15f) else Color.Transparent)
                        .border(
                            1.dp, 
                            if (activeTabSelection == 1) Color(0xFF00C853).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.05f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { activeTabSelection = 1 }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "SYLLABUS SKILL TREE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeTabSelection == 1) Color(0xFF00C853) else Color.Gray,
                        letterSpacing = 1.sp
                    )
                }
            }

            if (activeTabSelection == 0) {
                // ACCOUNTABILITY ARENA VIEW
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    // Elegant Header Block
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color(0xFF1A1A24), Color(0xFF0F0F11))
                                    )
                                )
                                .padding(horizontal = 20.dp, vertical = 24.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "CA INTER ARENA",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFFB300),
                                        letterSpacing = 2.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Weekly Accountability Ring",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFFFB300).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.EmojiEvents,
                                        contentDescription = "Arena Trophy",
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Real-time study metrics computed 100% locally. Gifting Streak Shields protects friends' daily focus loops.",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                lineHeight = 16.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Interactive Personal Streak & Active Shields Panel
                            val myRank = leaderboard.find { it.isMe }
                            val myStreak = myRank?.activeStreak ?: 0

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(12.dp))
                                    .clickable {
                                        showShieldsBottomSheet = true
                                    }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "🔥",
                                        fontSize = 24.sp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Your Streak",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "$myStreak Days",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (activeShields.isNotEmpty()) Color(0xFF0288D1).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = "Streak Shield",
                                        tint = if (activeShields.isNotEmpty()) Color(0xFF03A9F4) else Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Shields: ${activeShields.size}/2",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (activeShields.isNotEmpty()) Color(0xFF03A9F4) else Color.Gray
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Details",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Podium Section (Top 3)
                    if (leaderboard.isNotEmpty()) {
                        item {
                            val top3 = leaderboard.take(3)
                            ArenaPodium(
                                top3 = top3, 
                                activeShields = activeShields, 
                                onGiftShieldClick = {
                                    giftingPeer = it
                                    showGiftConfirmDialog = true
                                },
                                onShowShieldsBottomSheet = {
                                    showShieldsBottomSheet = true
                                }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    } else {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.02f))
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color(0xFFFFB300), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Loading Arena Rankings...",
                                        fontSize = 13.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    // Peer Ranking List (4th onwards)
                    if (leaderboard.size > 3) {
                        val remainingPeers = leaderboard.drop(3)
                        item {
                            Text(
                                text = "COMPETITORS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.LightGray,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }

                        items(remainingPeers) { peer ->
                            LeaderboardRow(
                                peer = peer, 
                                activeShields = activeShields, 
                                onGiftShieldClick = {
                                    giftingPeer = it
                                    showGiftConfirmDialog = true
                                },
                                onShowShieldsBottomSheet = {
                                    showShieldsBottomSheet = true
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    // Subject Mastery Breakdown section
                    item {
                        Divider(
                            color = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "SUBJECT MASTERY INDEX",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00C853),
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = when (masteryPeriod) {
                                        "TODAY" -> "Today's Syllabus Focus"
                                        "MONTHLY" -> "30-Day Syllabus Target"
                                        else -> "Weekly Study Targets"
                                    },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            // 3-Option Toggle Option (TODAY, WEEKLY, MONTHLY)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (masteryPeriod == "TODAY") Color(0xFF00C853) else Color.Transparent)
                                        .clickable { masteryPeriod = "TODAY" }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "TODAY",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (masteryPeriod == "TODAY") Color.Black else Color.White
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (masteryPeriod == "WEEKLY") Color(0xFF00C853) else Color.Transparent)
                                        .clickable { masteryPeriod = "WEEKLY" }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "WEEKLY",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (masteryPeriod == "WEEKLY") Color.Black else Color.White
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (masteryPeriod == "MONTHLY") Color(0xFF00C853) else Color.Transparent)
                                        .clickable { masteryPeriod = "MONTHLY" }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "MONTHLY",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (masteryPeriod == "MONTHLY") Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }

                    if (masteryPeriod == "TODAY") {
                        item {
                            Text(
                                text = "TODAY'S LEADERBOARD (ALL SUBJECTS)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }

                        if (todayRanks.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.02f))
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No study activity recorded today yet.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(todayRanks) { index, peer ->
                                val rank = index + 1
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (peer.isMe) Color(0xFF16251B) else Color(0xFF16161A)
                                    ),
                                    border = if (peer.isMe) BorderStroke(1.dp, Color(0xFF00C853).copy(alpha = 0.3f)) else null
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = when (rank) {
                                                    1 -> "🥇 1st"
                                                    2 -> "🥈 2nd"
                                                    3 -> "🥉 3rd"
                                                    else -> "🏅 ${rank}th"
                                                },
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = when (rank) {
                                                    1 -> Color(0xFFFFB300)
                                                    2 -> Color(0xFFB0BEC5)
                                                    3 -> Color(0xFFFFAB91)
                                                    else -> Color.Gray
                                                },
                                                modifier = Modifier.width(60.dp)
                                            )

                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.06f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = peer.customEmoji.ifEmpty { peer.displayName.take(2).uppercase() },
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Text(
                                                text = peer.displayName,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (peer.isMe) Color(0xFF00C853) else Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        val formattedTime = com.example.api.TimelineSyncEngine.formatTimeMsToHhMmSs(peer.todayFocusMs)
                                        Text(
                                            text = formattedTime,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (peer.isMe) Color(0xFF00C853) else Color.White
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "YOUR SUBJECT-WISE STUDY DETAILS (TODAY)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00C853),
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                    }

                    items(masteryStats) { subjectStats ->
                        SubjectMasteryProgressBar(stats = subjectStats)
                    }
                }
            } else {
                // SYLLABUS SKILL TREE VIEW
                SyllabusTreeScreen(viewModel = viewModel)
            }
        }
    }

    // Modal Bottom Sheet Showing Gifting Details
    if (showShieldsBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showShieldsBottomSheet = false },
            containerColor = Color(0xFF16161A),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp)
            ) {
                Text(
                    text = "ACTIVE STREAK SHIELDS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF03A9F4),
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Shields protect your daily study streak when you miss a 2-hour target. You can hold a maximum of 2 unconsumed shields.",
                    fontSize = 13.sp,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (activeShields.isEmpty()) {
                    Text(
                        text = "No active shields. Ask a peer to gift you one!",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                } else {
                    activeShields.forEach { shield ->
                        val dateFormatted = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(java.util.Date(shield.granted_timestamp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.04f))
                                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Gifted by: ${shield.donor_name}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Email: ${shield.donor_email}",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            Text(
                                text = dateFormatted,
                                fontSize = 12.sp,
                                color = Color(0xFF03A9F4),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showShieldsBottomSheet = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03A9F4)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Gift Confirmation Dialog
    if (showGiftConfirmDialog && giftingPeer != null) {
        AlertDialog(
            onDismissRequest = { 
                if (!isGiftingInProgress) {
                    showGiftConfirmDialog = false
                    giftingPeer = null
                    giftErrorMsg = null
                    giftSuccessMsg = null
                }
            },
            title = { Text("Gift Streak Shield?", color = Color.White) },
            text = { 
                Column {
                    Text(
                        "Would you like to gift a Streak Shield to ${giftingPeer?.displayName}?\n\nThis will deduct 500 XP from your total score to prevent spamming.",
                        color = Color.LightGray
                    )
                    if (isGiftingInProgress) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator(color = Color(0xFFFFB300), modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    giftErrorMsg?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(error, color = Color.Red, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    giftSuccessMsg?.let { success ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(success, color = Color(0xFF00C853), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            },
            confirmButton = {
                if (giftSuccessMsg == null) {
                    TextButton(
                        enabled = !isGiftingInProgress,
                        onClick = {
                            isGiftingInProgress = true
                            giftErrorMsg = null
                            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            val username = prefs.getString("current_username", "Guest") ?: "Guest"
                            val cachedNickname = prefs.getString("user_nickname_$username", "") ?: ""
                            val cachedName = prefs.getString("user_name_$username", "") ?: ""
                            val resolvedName = if (cachedNickname.isNotEmpty()) cachedNickname else if (cachedName.isNotEmpty()) cachedName else username

                            StreakShieldManager.giftShield(
                                context = context,
                                senderEmail = email,
                                senderName = resolvedName,
                                friendEmail = giftingPeer!!.email,
                                onSuccess = {
                                    isGiftingInProgress = false
                                    giftSuccessMsg = "Shield successfully gifted! 500 XP deducted."
                                    // Trigger stats update to subtract XP locally
                                    StreakShieldManager.fetchAndSyncShields(context, email)
                                },
                                onFailure = { ex ->
                                    isGiftingInProgress = false
                                    giftErrorMsg = ex.message ?: "An error occurred"
                                }
                            )
                        }
                    ) {
                        Text("Confirm (-500 XP)", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(
                        onClick = {
                            showGiftConfirmDialog = false
                            giftingPeer = null
                            giftSuccessMsg = null
                        }
                    ) {
                        Text("Done", color = Color.White)
                    }
                }
            },
            dismissButton = {
                if (giftSuccessMsg == null) {
                    TextButton(
                        enabled = !isGiftingInProgress,
                        onClick = { showGiftConfirmDialog = false; giftingPeer = null }
                    ) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            },
            containerColor = Color(0xFF1E1E24)
        )
    }
}

@Composable
fun ArenaPodium(
    top3: List<ArenaRankModel>,
    activeShields: List<LocalShieldsVault>,
    onGiftShieldClick: (ArenaRankModel) -> Unit,
    onShowShieldsBottomSheet: () -> Unit
) {
    val podiumOrder = remember(top3) {
        val list = mutableListOf<ArenaRankModel?>()
        if (top3.size > 1) list.add(top3[1]) else list.add(null) // 2nd Place
        if (top3.isNotEmpty()) list.add(top3[0]) else list.add(null) // 1st Place
        if (top3.size > 2) list.add(top3[2]) else list.add(null) // 3rd Place
        list
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        podiumOrder.forEachIndexed { index, peer ->
            val isFirst = index == 1
            val isSecond = index == 0
            val isThird = index == 2

            val podiumHeight = if (isFirst) 210.dp else if (isSecond) 175.dp else 155.dp
            val medalColor = if (isFirst) Color(0xFFFFD700) else if (isSecond) Color(0xFFBDC3C7) else Color(0xFFCD7F32)
            val rankText = if (isFirst) "1st" else if (isSecond) "2nd" else "3rd"

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(podiumHeight)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = if (isFirst) {
                                listOf(Color(0xFF2C2512), Color(0xFF14141A))
                            } else {
                                listOf(Color(0xFF1E1E24), Color(0xFF111116))
                            }
                        )
                    )
                    .border(
                        width = if (peer?.isMe == true) 2.dp else 1.dp,
                        brush = if (peer?.isMe == true) {
                            Brush.linearGradient(listOf(Color(0xFF00C853), Color(0xFF00E676)))
                        } else {
                            Brush.linearGradient(listOf(medalColor.copy(alpha = 0.6f), Color.Transparent))
                        },
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .padding(8.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                if (peer != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Badge / Rank Label
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(medalColor.copy(alpha = 0.2f))
                                .border(1.dp, medalColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = rankText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = medalColor
                              )
                        }

                        // Initials Avatar
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(medalColor.copy(alpha = 0.15f))
                                .border(1.5.dp, medalColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = peer.displayName.take(2).uppercase(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }

                        // User Info
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Text(
                                text = peer.displayName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (peer.isMe) Color(0xFF00C853) else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = formatFocusMsToHours(peer.totalFocusMs),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.LightGray
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = "${peer.xpScore} XP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFFFB300)
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (peer.activeStreak > 0) {
                                    Text(
                                        text = "🔥 ${peer.activeStreak}d",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF7043)
                                    )
                                }

                                if (peer.isMe && activeShields.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = "Shield Active",
                                        tint = Color(0xFF03A9F4),
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clickable { onShowShieldsBottomSheet() }
                                    )
                                }
                            }

                            // Gift shield button if not me
                            if (!peer.isMe) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFFFFB300).copy(alpha = 0.15f))
                                        .clickable { onGiftShieldClick(peer) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CardGiftcard,
                                            contentDescription = "Gift Shield",
                                            tint = Color(0xFFFFB300),
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = "Gift",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFFB300)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Empty Podium Spot
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = rankText,
                            fontSize = 12.sp,
                            color = Color.DarkGray,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Invite Partner",
                            tint = Color.DarkGray,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Unoccupied",
                            fontSize = 10.sp,
                            color = Color.DarkGray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LeaderboardRow(
    peer: ArenaRankModel,
    activeShields: List<LocalShieldsVault>,
    onGiftShieldClick: (ArenaRankModel) -> Unit,
    onShowShieldsBottomSheet: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (peer.isMe) Color(0xFF16251B) else Color(0xFF16161A)
        ),
        border = if (peer.isMe) BorderStroke(1.dp, Color(0xFF00C853).copy(alpha = 0.3f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Rank, Avatar and Name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${peer.rank}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Gray,
                    modifier = Modifier.width(24.dp)
                )

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peer.displayName.take(2).uppercase(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = peer.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (peer.isMe) Color(0xFF00C853) else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (peer.topSubject != "None") {
                        Text(
                            text = "Top: ${peer.topSubject}",
                            fontSize = 10.sp,
                            color = Color.LightGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Stats and Gift Option
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (peer.activeStreak > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFF7043).copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "🔥 ${peer.activeStreak}d",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF7043)
                            )
                        }
                    }

                    if (peer.isMe && activeShields.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Shield Active",
                            tint = Color(0xFF03A9F4),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onShowShieldsBottomSheet() }
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatFocusMsToHours(peer.totalFocusMs),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${peer.xpScore} XP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFFB300)
                    )
                }

                if (!peer.isMe) {
                    IconButton(
                        onClick = { onGiftShieldClick(peer) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CardGiftcard,
                            contentDescription = "Gift Streak Shield",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SubjectMasteryProgressBar(stats: SubjectMasteryStats) {
    val totalHours = stats.totalFocusMs / 3600000.0
    
    val barColor = remember(stats.subjectName) {
        when {
            stats.subjectName.contains("Paper 1") -> Color(0xFF42A5F5)
            stats.subjectName.contains("Paper 2") -> Color(0xFF9CCC65)
            stats.subjectName.contains("Paper 3") -> Color(0xFFAB47BC)
            stats.subjectName.contains("Paper 4") -> Color(0xFFFF7043)
            stats.subjectName.contains("Paper 5") -> Color(0xFF26A69A)
            else -> Color(0xFFEC407A)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(barColor, shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stats.subjectName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "${String.format(Locale.US, "%.1f", totalHours)} hrs studied",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = barColor
            )
        }
    }
}

@Composable
fun SyllabusSkillTreeCanvas(historyRecords: List<LocalHistoryVault>) {
    val skillTreeNodes = remember(historyRecords) {
        SyllabusSkillTreeEngine.calculateSyllabusMastery(historyRecords)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "CA INTER SYLLABUS MASTERY MAP",
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF00C853),
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Study 5 hours in any sub-topic to unlock its mastery node.",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(skillTreeNodes) { node ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        // Paper Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Paper ${node.subject.paperNumber}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFB300)
                                )
                                Text(
                                    text = node.subject.subjectName.substringAfter(": "),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            
                            val subjectHours = node.totalFocusMs / 3600000.0
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.1f Hrs Total", subjectHours),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.LightGray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = Color.White.copy(alpha = 0.06f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Subtopics Tree Nodes
                        Text(
                            text = "SYLLABUS CHAPTERS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        node.subTopics.forEach { subTopic ->
                            val subTopicHours = subTopic.totalFocusMs / 3600000.0
                            val progress = (subTopicHours / 5.0).toFloat().coerceIn(0f, 1f) // Target: 5 Hours to Unlock

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (subTopic.isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                        contentDescription = if (subTopic.isUnlocked) "Unlocked" else "Locked",
                                        tint = if (subTopic.isUnlocked) Color(0xFF00C853) else Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = subTopic.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (subTopic.isUnlocked) Color(0xFF00C853) else Color.White
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        // Simple Progress Bar
                                        LinearProgressIndicator(
                                            progress = progress,
                                            color = if (subTopic.isUnlocked) Color(0xFF00C853) else Color(0xFFFFB300),
                                            trackColor = Color.White.copy(alpha = 0.05f),
                                            modifier = Modifier
                                                .width(120.dp)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = String.format(Locale.US, "%.1f / 5.0 hrs", subTopicHours),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (subTopic.isUnlocked) Color(0xFF00C853) else Color.Gray
                                    )
                                    if (subTopic.isUnlocked) {
                                        Text(
                                            text = "EMERALD MASTERED",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00C853),
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper formatting logic
private fun formatFocusMsToHours(ms: Long): String {
    val hours = ms / 3600000.0
    return String.format(Locale.US, "%.1f hrs", hours)
}

private fun getSyllabusMasteryForPeriod(
    records: List<LocalHistoryVault>,
    period: String
): List<SubjectMasteryStats> {
    val calendar = Calendar.getInstance()
    
    // Calculate the cut-off timestamp
    val cutoffMs = when (period) {
        "TODAY" -> {
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }
        "MONTHLY" -> {
            calendar.add(Calendar.DAY_OF_YEAR, -30)
            calendar.timeInMillis
        }
        else -> { // WEEKLY
            // Find Monday of current week
            calendar.firstDayOfWeek = Calendar.MONDAY
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }
    }

    val periodRecords = records.filter { it.start_time_ms >= cutoffMs }

    // Map to CaInterSubject and group
    val grouped = CaInterSubject.values().associateWith { 0L }.toMutableMap()
    
    for (record in periodRecords) {
        val mappedSubject = CaInterSubject.fromTag(record.subject)
        if (mappedSubject != null) {
            val currentVal = grouped[mappedSubject] ?: 0L
            grouped[mappedSubject] = currentVal + record.total_focus_ms
        }
    }

    return grouped.map { (subject, totalMs) ->
        SubjectMasteryStats(
            subjectName = subject.subjectName,
            totalFocusMs = totalMs
        )
    }
}

data class TodayRankModel(
    val email: String,
    val displayName: String,
    val todayFocusMs: Long,
    val isMe: Boolean,
    val customEmoji: String
)

