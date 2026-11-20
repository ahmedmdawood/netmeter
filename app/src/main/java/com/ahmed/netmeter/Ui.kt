package com.ahmed.netmeter

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



val Background = Color(0xFF0F1420)
val SurfaceCard = Color(0xFF1A2233)
val SurfaceCardLight = Color(0xFF232D42)
val Accent = Color(0xFF2DD4BF)
val AccentSecondary = Color(0xFF818CF8)
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)

private val NetMeterColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF06231F),
    secondary = AccentSecondary,
    onSecondary = Color(0xFF1B1B3A),
    background = Background,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCardLight,
    onSurfaceVariant = TextSecondary,
)

@Composable
fun NetMeterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NetMeterColors,
        content = content
    )
}



data class DayUiState(
    val date: LocalDate = LocalDate.now(),
    val totalBytes: Long = 0L,
    val apps: List<AppUsage> = emptyList(),
    val loading: Boolean = true,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val db = UsageDatabase.get(app)

    private val _hasPermission = MutableStateFlow(UsageStatsHelper.hasUsageAccess(app))
    val hasPermission: StateFlow<Boolean> = _hasPermission

    private val _networkType = MutableStateFlow(prefs.networkType)
    val networkType: StateFlow<Int> = _networkType

    private val _retentionMonths = MutableStateFlow(prefs.retentionMonths)
    val retentionMonths: StateFlow<Int> = _retentionMonths

    private val _dayState = MutableStateFlow(DayUiState())
    val dayState: StateFlow<DayUiState> = _dayState

    private val _selectedHour = MutableStateFlow<Int?>(null)
    val selectedHour: StateFlow<Int?> = _selectedHour

    private val _hourApps = MutableStateFlow<List<AppUsage>>(emptyList())
    val hourApps: StateFlow<List<AppUsage>> = _hourApps

    private val _dailyTotals = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())
    val dailyTotals: StateFlow<Map<LocalDate, Long>> = _dailyTotals

    init {
        refreshAll()
    }

    fun onResume() {
        val granted = UsageStatsHelper.hasUsageAccess(getApplication())
        _hasPermission.value = granted
        if (granted) refreshAll()
    }

    fun setNetworkType(type: Int) {
        prefs.networkType = type
        _networkType.value = type
        refreshAll()
    }

    fun setRetentionMonths(months: Int) {
        prefs.retentionMonths = months
        _retentionMonths.value = months
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = LocalDate.now().minusMonths(months.toLong()).toString()
            db.snapshotDao().pruneBefore(cutoff)
        }
    }

    fun selectDate(date: LocalDate) {
        val today = LocalDate.now()
        val safe = if (date.isAfter(today)) today else date
        _selectedHour.value = null
        _hourApps.value = emptyList()
        loadDay(safe)
    }

    fun shiftDay(days: Long) = selectDate(_dayState.value.date.plusDays(days))

    fun selectHour(hour: Int?) {
        _selectedHour.value = hour
        if (hour == null) {
            _hourApps.value = emptyList()
            return
        }
        val date = _dayState.value.date
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val (hs, he) = UsageStatsHelper.hourRange(date, hour)
                val (ds, de) = UsageStatsHelper.dayRange(date)
                UsageStatsHelper.queryHourlyAppUsage(
                    getApplication(), _networkType.value, hs, he, ds, de
                )
            }
            _hourApps.value = apps
        }
    }

    fun refreshAll() {
        if (!_hasPermission.value) return
        loadDay(_dayState.value.date)
        loadTotals()
        recordSnapshot()
    }

    private fun loadDay(date: LocalDate) {
        viewModelScope.launch {
            _dayState.value = DayUiState(date = date, loading = true)
            val apps = withContext(Dispatchers.IO) {
                val (start, end) = UsageStatsHelper.dayRange(date)
                UsageStatsHelper.queryAppUsage(getApplication(), _networkType.value, start, end)
            }
            _dayState.value = DayUiState(
                date = date,
                totalBytes = apps.sumOf { it.bytes },
                apps = apps,
                loading = false,
            )
        }
    }

    private fun loadTotals() {
        viewModelScope.launch {
            val totals = withContext(Dispatchers.IO) {
                val end = LocalDate.now().plusDays(1)
                val (start, _) = UsageStatsHelper.dayRange(end.minusDays(31))
                val (_, stop) = UsageStatsHelper.dayRange(LocalDate.now())
                UsageStatsHelper.queryDailyTotals(getApplication(), _networkType.value, start, stop)
            }
            _dailyTotals.value = totals
        }
    }

    private fun recordSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val today = LocalDate.now()
                val total = _dailyTotals.value[today] ?: return@launch
                db.snapshotDao().upsert(
                    DailySnapshot(
                        date = today.toString(),
                        networkType = _networkType.value,
                        totalBytes = total,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } catch (e: Exception) {
                // Snapshot recording is best-effort.
            }
        }
    }
}



fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatHour(hour: Int): String {
    val suffix = if (hour < 12) "AM" else "PM"
    val h = hour % 12
    return "${if (h == 0) 12 else h} $suffix"
}

/** One row: app icon, name, animated usage bar, formatted total. */
@Composable
fun AppUsageRow(usage: AppUsage, maxBytes: Long) {
    val target = if (maxBytes <= 0L) 0f else (usage.bytes.toFloat() / maxBytes.toFloat())
    val fraction by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "bar"
    )
    val icon: ImageBitmap? = remember(usage.packageName) {
        try {
            usage.icon?.toBitmap()?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCardLight),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = usage.appName,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Accent.copy(alpha = 0.2f))
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    usage.appName,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Accent.copy(alpha = 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Accent)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                formatBytes(usage.bytes),
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Animated bar chart. [data] is label -> bytes, oldest first. */
@Composable
fun UsageBarChart(data: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    if (data.isEmpty()) return
    val max = (data.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(data) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
    }

    Row(
        modifier = modifier.height(150.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        data.forEach { (label, value) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier
                    .weight(1f)
                    .height(150.dp)
            ) {
                val ratio = value.toFloat() / max.toFloat() * progress.value
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((118f * ratio).coerceAtLeast(2f).dp)
                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                        .background(
                            if (value == max) Accent else Accent.copy(alpha = 0.45f)
                        )
                )
                Text(
                    label,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}



/** Shown until the user grants Usage Access in system settings. */
@Composable
fun OnboardingScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "One permission, once",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Android requires apps that measure data usage to get \"Usage Access\" from a system settings screen. Tap below, find NetMeter in the list, and switch it on.",
            color = TextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { context.startActivity(UsageStatsHelper.usageAccessSettingsIntent()) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text("Open Usage Access settings", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(28.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                "Your usage data never leaves your phone. Everything is stored locally.",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.made_by),
            color = TextSecondary,
            fontSize = 13.sp
        )
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(viewModel: MainViewModel) {
    val day by viewModel.dayState.collectAsState()
    val selectedHour by viewModel.selectedHour.collectAsState()
    val hourApps by viewModel.hourApps.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val isToday = day.date == today
    val title = when {
        isToday -> "Today"
        day.date == today.minusDays(1) -> "Yesterday"
        else -> day.date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.selectDate(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        )
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // Day navigation
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.shiftDay(-1) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous day",
                                tint = TextPrimary
                            )
                        }
                        Text(
                            title,
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showPicker = true },
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        IconButton(
                            onClick = { viewModel.shiftDay(1) },
                            enabled = !isToday
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next day",
                                tint = if (isToday) TextSecondary.copy(alpha = 0.35f) else TextPrimary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    AnimatedContent(
                        targetState = day,
                        transitionSpec = {
                            slideInHorizontally { it } + fadeIn() togetherWith
                                slideOutHorizontally { -it } + fadeOut() using
                                SizeTransform(clip = false)
                        },
                        label = "dayContent"
                    ) { state ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.loading) "..." else formatBytes(state.totalBytes),
                                color = Accent,
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "total used",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // Hour picker
        item {
            Text("Pick an hour to zoom in", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(24) { hour ->
                    FilterChip(
                        selected = selectedHour == hour,
                        onClick = {
                            viewModel.selectHour(if (selectedHour == hour) null else hour)
                        },
                        label = { Text(formatHour(hour)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = TextPrimary
                        )
                    )
                }
            }
        }

        // Per-app list
        item {
            Text(
                if (selectedHour == null) "Apps" else "Apps at ${formatHour(selectedHour!!)}",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        val appsToShow = if (selectedHour == null) day.apps else hourApps
        val maxBytes = appsToShow.maxOfOrNull { it.bytes } ?: 0L

        if (day.loading && selectedHour == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            }
        } else if (appsToShow.isEmpty()) {
            item {
                Text(
                    "No usage recorded for this time.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(appsToShow, key = { it.packageName }) { usage ->
                AppUsageRow(usage = usage, maxBytes = maxBytes)
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}



@Composable
fun StatsScreen(viewModel: MainViewModel) {
    val dailyTotals by viewModel.dailyTotals.collectAsState()
    var days by remember { mutableIntStateOf(7) }

    val today = LocalDate.now()
    val dates = (0 until days).map { today.minusDays((days - 1 - it).toLong()) }
    val chartData = dates.map { date ->
        val label = if (days == 7) {
            date.format(DateTimeFormatter.ofPattern("EEE"))
        } else {
            date.format(DateTimeFormatter.ofPattern("d/M"))
        }
        label to (dailyTotals[date] ?: 0L)
    }
    val periodTotal = chartData.sumOf { it.second }
    val bestDay = chartData.maxByOrNull { it.second }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = days == 7,
                onClick = { days = 7 },
                label = { Text("Week") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent,
                    selectedLabelColor = TextPrimary
                )
            )
            FilterChip(
                selected = days == 30,
                onClick = { days = 30 },
                label = { Text("Month") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent,
                    selectedLabelColor = TextPrimary
                )
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (days == 7) "Last 7 days" else "Last 30 days",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(16.dp))
                UsageBarChart(data = chartData, modifier = Modifier.fillMaxWidth())
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    Text("Period total", color = TextSecondary, modifier = Modifier.weight(1f))
                    Text(formatBytes(periodTotal), color = Accent, fontWeight = FontWeight.Bold)
                }
                Row {
                    Text("Daily average", color = TextSecondary, modifier = Modifier.weight(1f))
                    Text(
                        formatBytes(periodTotal / days),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row {
                    Text("Heaviest day", color = TextSecondary, modifier = Modifier.weight(1f))
                    Text(
                        bestDay?.let { "${it.first} (${formatBytes(it.second)})" } ?: "-",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Text(
            "Longer history builds up day by day and is kept for the retention period you choose in Settings.",
            color = TextSecondary,
            fontSize = 13.sp
        )
    }
}



private val RETENTION_OPTIONS = listOf(1, 3, 6, 9, 12)

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val retention by viewModel.retentionMonths.collectAsState()
    val networkType by viewModel.networkType.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // Network type
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Measure", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                listOf(
                    UsageStatsHelper.NETWORK_MOBILE to "Mobile data",
                    UsageStatsHelper.NETWORK_WIFI to "Wi-Fi"
                ).forEach { (type, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = networkType == type,
                            onClick = { viewModel.setNetworkType(type) },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent)
                        )
                        Text(label, color = TextPrimary)
                    }
                }
            }
        }

        // Retention
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Keep my history for",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Older records are deleted automatically.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                RETENTION_OPTIONS.forEach { months ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = retention == months,
                            onClick = { viewModel.setRetentionMonths(months) },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent)
                        )
                        Text(
                            if (months == 12) "1 year" else "$months ${if (months == 1) "month" else "months"}",
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        // About
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("NetMeter", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Version 1.0", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.made_by),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
