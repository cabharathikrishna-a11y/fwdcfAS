package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.api.*
import com.example.data.*
import com.example.ui.AppViewModel

@Composable
fun SyllabusTreeScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val WaterBlue = Color(0xFF38BDF8)
    val GreenSuccess = Color(0xFF10B981)
    
    val completedVaultList by viewModel.syllabusCompletionFlow.collectAsStateWithLifecycle()
    val completedTopicIds = remember(completedVaultList) {
        completedVaultList.filter { it.isCompleted }.map { it.topicId }.toSet()
    }

    val syllabusCompletionStats = remember(completedTopicIds) {
        var totalCh = 0
        var completedCh = 0
        var totalTp = 0
        var completedTp = 0
        CAInterSubject.entries.forEach { subject ->
            val chapters = SyllabusRegistry.getChaptersForSubject(subject)
            chapters.forEach { chapter ->
                totalCh++
                val subtopics = SyllabusRegistry.getSubTopicsForChapter(subject, chapter)
                val totalInChapter = subtopics.size
                val completedInChapter = subtopics.count { completedTopicIds.contains(it.topicId) }
                totalTp += totalInChapter
                completedTp += completedInChapter
                if (totalInChapter > 0 && completedInChapter == totalInChapter) {
                    completedCh++
                }
            }
        }
        Pair(Pair(completedCh, totalCh), Pair(completedTp, totalTp))
    }
    val (chapterStats, topicStats) = syllabusCompletionStats
    val (completedChapters, totalChapters) = chapterStats
    val (completedTopics, totalTopics) = topicStats

    var searchQuery by remember { mutableStateOf("") }
    var selectedSubjectForDetail by remember { mutableStateOf<CAInterSubject?>(null) }
    
    val expandedChapters = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        if (selectedSubjectForDetail == null) {
            // ================== MAIN LEVEL: LIST OF SUBJECTS ==================
            // Real-time completion progress header
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111113)),
                border = BorderStroke(1.dp, Color(0xFF232326)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "OVERALL SYLLABUS PROGRESS",
                                color = WaterBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Completed Chapters",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "$completedChapters / $totalChapters",
                            color = WaterBlue,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (totalChapters > 0) completedChapters.toFloat() / totalChapters.toFloat() else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (completedChapters == totalChapters && totalChapters > 0) GreenSuccess else WaterBlue,
                        trackColor = Color(0xFF222225)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Completed Topics: $completedTopics / $totalTopics",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        val percent = if (totalTopics > 0) (completedTopics * 100) / totalTopics else 0
                        Text(
                            text = "$percent% of syllabus done",
                            color = if (percent == 100) GreenSuccess else Color.LightGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Text(
                text = "SUBJECT SYLLABUS TREES",
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(CAInterSubject.entries) { subject ->
                    val chapters = SyllabusRegistry.getChaptersForSubject(subject)
                    val allSubtopicsInSubject = chapters.flatMap { SyllabusRegistry.getSubTopicsForChapter(subject, it) }
                    val totalInSubject = allSubtopicsInSubject.size
                    val completedInSubject = allSubtopicsInSubject.count { completedTopicIds.contains(it.topicId) }
                    val subjectPercent = if (totalInSubject > 0) (completedInSubject * 100) / totalInSubject else 0

                    val totalChaptersInSubject = chapters.size
                    val completedChaptersInSubject = chapters.count { ch ->
                        val sub = SyllabusRegistry.getSubTopicsForChapter(subject, ch)
                        sub.isNotEmpty() && sub.all { completedTopicIds.contains(it.topicId) }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSubjectForDetail = subject },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111113)),
                        border = BorderStroke(1.dp, Color(0xFF232326))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = subject.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$completedChaptersInSubject / $totalChaptersInSubject Chapters",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "$completedInSubject / $totalInSubject Topics",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LinearProgressIndicator(
                                        progress = { completedInSubject.toFloat() / totalInSubject.coerceAtLeast(1).toFloat() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = if (subjectPercent == 100) GreenSuccess else WaterBlue,
                                        trackColor = Color(0xFF222225)
                                    )
                                    Text(
                                        text = "$subjectPercent%",
                                        color = if (subjectPercent == 100) GreenSuccess else Color.LightGray,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = "View Syllabus",
                                tint = Color.Gray,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // ================== SUBJECT DETAIL LEVEL ==================
            val subject = selectedSubjectForDetail!!
            val chapters = SyllabusRegistry.getChaptersForSubject(subject)
            val allSubtopicsInSubject = chapters.flatMap { SyllabusRegistry.getSubTopicsForChapter(subject, it) }
            val totalInSubject = allSubtopicsInSubject.size
            val completedInSubject = allSubtopicsInSubject.count { completedTopicIds.contains(it.topicId) }
            val subjectPercent = if (totalInSubject > 0) (completedInSubject * 100) / totalInSubject else 0
            
            val totalChaptersInSubject = chapters.size
            val completedChaptersInSubject = chapters.count { ch ->
                val sub = SyllabusRegistry.getSubTopicsForChapter(subject, ch)
                sub.isNotEmpty() && sub.all { completedTopicIds.contains(it.topicId) }
            }

            // Top Back button & title row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { selectedSubjectForDetail = null },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back to Subjects",
                        tint = WaterBlue
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = subject.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Subject level progress card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111113)),
                border = BorderStroke(1.dp, Color(0xFF232326)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SUBJECT COMPLETION",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "$completedInSubject / $totalInSubject Topics",
                            color = WaterBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { completedInSubject.toFloat() / totalInSubject.coerceAtLeast(1).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (subjectPercent == 100) GreenSuccess else WaterBlue,
                        trackColor = Color(0xFF222225)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$completedChaptersInSubject of $totalChaptersInSubject Chapters Completed",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }

            // Search box inside the subject detail page
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search chapters or topics...", color = Color.Gray, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = WaterBlue,
                    unfocusedBorderColor = Color(0xFF232326),
                    focusedContainerColor = Color(0xFF111113),
                    unfocusedContainerColor = Color(0xFF111113)
                ),
                singleLine = true
            )

            // Filtering chapters & subtopics
            val filteredChapters = if (searchQuery.isEmpty()) {
                chapters
            } else {
                chapters.filter { chapter ->
                    chapter.contains(searchQuery, ignoreCase = true) ||
                    SyllabusRegistry.getSubTopicsForChapter(subject, chapter).any { subtopic ->
                        subtopic.subTopicTitle.contains(searchQuery, ignoreCase = true)
                    }
                }
            }

            // Expanded chapters initialized to true for convenience if searching
            if (searchQuery.isNotEmpty()) {
                filteredChapters.forEach { chapter ->
                    val chapterKey = "${subject.name}_${chapter}"
                    expandedChapters[chapterKey] = true
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filteredChapters.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matching chapters or topics found.",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    filteredChapters.forEach { chapter ->
                        val subtopics = SyllabusRegistry.getSubTopicsForChapter(subject, chapter)
                        val totalInChapter = subtopics.size
                        val completedInChapter = subtopics.count { completedTopicIds.contains(it.topicId) }
                        val isChapterAllCompleted = totalInChapter > 0 && completedInChapter == totalInChapter

                        val chapterKey = "${subject.name}_${chapter}"
                        val isChapterExpanded = expandedChapters[chapterKey] ?: false

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0C0C0E), RoundedCornerShape(8.dp))
                                    .border(BorderStroke(1.dp, Color(0xFF1E1E22)), RoundedCornerShape(8.dp))
                                    .clickable { expandedChapters[chapterKey] = !isChapterExpanded }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChapterAllCompleted,
                                    onCheckedChange = { checkAll ->
                                        subtopics.forEach { subtopic ->
                                            viewModel.updateSyllabusCompletion(subtopic.topicId, checkAll)
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = GreenSuccess,
                                        uncheckedColor = Color.Gray,
                                        checkmarkColor = Color.Black
                                    ),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chapter,
                                        color = if (isChapterAllCompleted) GreenSuccess else Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$completedInChapter / $totalInChapter topics completed",
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                }
                                Icon(
                                    imageVector = if (isChapterExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Expand/Collapse Chapter",
                                    tint = Color.DarkGray
                                )
                            }
                        }

                        if (isChapterExpanded) {
                            val filteredSubtopics = if (searchQuery.isEmpty()) {
                                subtopics
                            } else {
                                subtopics.filter { it.subTopicTitle.contains(searchQuery, ignoreCase = true) }
                            }

                            items(filteredSubtopics) { subtopic ->
                                val isCompleted = completedTopicIds.contains(subtopic.topicId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                                        .background(Color(0xFF070709), RoundedCornerShape(6.dp))
                                        .clickable { viewModel.updateSyllabusCompletion(subtopic.topicId, !isCompleted) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isCompleted,
                                        onCheckedChange = { isChecked ->
                                            viewModel.updateSyllabusCompletion(subtopic.topicId, isChecked)
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = GreenSuccess,
                                            uncheckedColor = Color.DarkGray,
                                            checkmarkColor = Color.Black
                                        ),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = subtopic.subTopicTitle,
                                        color = if (isCompleted) Color.Gray else Color.LightGray,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        modifier = Modifier.weight(1f)
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

