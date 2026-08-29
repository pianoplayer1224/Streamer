package com.streamer.timetable

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamer.timetable.data.NotificationRule
import com.streamer.timetable.data.SCHOOL_ZONE
import com.streamer.timetable.ui.AppDrawer
import com.streamer.timetable.ui.EventDetailSheet
import com.streamer.timetable.ui.LoginScreen
import com.streamer.timetable.ui.NotificationsScreen
import com.streamer.timetable.ui.RuleEditorScreen
import com.streamer.timetable.ui.SiteLink
import com.streamer.timetable.ui.TimetableScreen
import com.streamer.timetable.ui.TimetableTab
import com.streamer.timetable.ui.TimetableViewModel
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StreamerApp() }
    }
}

@Composable
private fun StreamerApp() {
    val dark = (LocalConfiguration.current.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(primary = Color(0xFFFC7C00))
        } else {
            lightColorScheme(primary = Color(0xFF336D85))
        }
    ) {
        Root()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(viewModel: TimetableViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val enabledFeeds by viewModel.enabledFeeds.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val hideMusBlock by viewModel.hideMusBlock.collectAsStateWithLifecycle()
    val musBlockCount by viewModel.musBlockCount.collectAsStateWithLifecycle()
    val animateWeekScroll by viewModel.animateWeekScroll.collectAsStateWithLifecycle()
    val scrollToTodayRequests by viewModel.scrollToTodayRequests.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val syncStats by viewModel.syncStats.collectAsStateWithLifecycle()
    val daysBehind by viewModel.daysBehind.collectAsStateWithLifecycle()
    val daysAhead by viewModel.daysAhead.collectAsStateWithLifecycle()
    val syncIntervalHours by viewModel.syncIntervalHours.collectAsStateWithLifecycle()

    // Which screen the drawer has navigated to. Null means the timetable itself.
    var editingRule by remember { mutableStateOf<NotificationRule?>(null) }
    var showNotifications by remember { mutableStateOf(false) }

    // Android 13 onward will not deliver notifications without this, and a silently
    // undelivered reminder is indistinguishable from a broken rule. Asked for when
    // the notifications screen is opened, which is the moment it makes sense.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(showNotifications) {
        if (showNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val debugDate by viewModel.debugDate.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val selectedEvent by viewModel.selectedEvent.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // A rejected password forces re-entry rather than retrying in the background,
    // so the user is never silently locked out of their AD account.
    val showLogin = !state.signedIn || state.needsReauth

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    if (showLogin) {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LoginScreen(
                    syncing = state.syncing,
                    onSignIn = { user, pass, domain, onResult ->
                        viewModel.signIn(user, pass, domain, onResult)
                    },
                )
            }
        }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                lastSyncedLabel = lastSyncedLabel(state.lastSyncedMillis),
                storedEventCount = state.storedEventCount,
                hideMusBlock = hideMusBlock,
                musBlockCount = musBlockCount,
                animateWeekScroll = animateWeekScroll,
                daysBehind = daysBehind,
                daysAhead = daysAhead,
                syncIntervalHours = syncIntervalHours,
                debugDate = debugDate,
                realToday = LocalDate.now(SCHOOL_ZONE),
                onOpenLink = { link ->
                    scope.launch { drawerState.close() }
                    openInBrowser(context, link)
                },
                onToggleMusBlock = viewModel::setHideMusBlock,
                onToggleAnimateWeekScroll = viewModel::setAnimateWeekScroll,
                onSetWindow = viewModel::setWindow,
                onSetSyncInterval = viewModel::setSyncIntervalHours,
                onOpenNotifications = {
                    scope.launch { drawerState.close() }
                    showNotifications = true
                },
                onSetDebugDate = viewModel::setDebugDate,
                diagnostics = diagnostics,
                syncStats = syncStats,
                onResetSyncStats = viewModel::resetSyncStats,
                onRefreshDiagnostics = viewModel::refreshDiagnostics,
                onTestNotification = viewModel::sendTestNotification,
                onScheduleTestAlert = viewModel::scheduleTestAlert,
                onOpenBatterySettings = {
                    scope.launch { drawerState.close() }
                    openSettings(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                },
                onOpenAlarmSettings = {
                    scope.launch { drawerState.close() }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        openSettings(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    }
                },
                crashLogCount = viewModel.crashLogCount(),
                onShareCrash = {
                    scope.launch { drawerState.close() }
                    // Shared as plain text rather than a file: no FileProvider to
                    // configure, and a stack trace pastes straight into a message.
                    viewModel.latestCrashText()?.let { shareText(context, it) }
                },
                onClearCrashes = {
                    scope.launch { drawerState.close() }
                    viewModel.clearCrashLogs()
                },
                onRefreshWidget = {
                    scope.launch { drawerState.close() }
                    viewModel.refreshWidget()
                },
                onAddSampleEvents = {
                    scope.launch { drawerState.close() }
                    viewModel.addSampleEvents()
                },
                onClearStored = {
                    scope.launch { drawerState.close() }
                    viewModel.clearStoredEvents()
                },
                onSignOut = {
                    scope.launch { drawerState.close() }
                    viewModel.signOut()
                },
            )
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = {
                            if (showNotifications) {
                                IconButton(onClick = {
                                    if (editingRule != null) editingRule = null
                                    else showNotifications = false
                                }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                    )
                                }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    when {
                                        editingRule != null -> "Notification rule"
                                        showNotifications -> "Notifications"
                                        else -> "Timetable"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    // Surfaces the debug override so a shifted date is
                                    // never mistaken for a real one.
                                    debugDate?.let { "Debug date: $it" }
                                        ?: lastSyncedLabel(state.lastSyncedMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        actions = {
                            if (showNotifications) return@TopAppBar
                            // Sync stays in the toolbar: it is the one action worth
                            // reaching without opening the drawer.
                            if (state.syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                IconButton(onClick = { viewModel.refresh() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Sync now")
                                }
                            }
                        },
                    )

                    if (!showNotifications) {
                        TabRow(
                            selectedTabIndex = TimetableTab.entries.indexOf(selectedTab),
                        ) {
                            TimetableTab.entries.forEach { tab ->
                                Tab(
                                    selected = tab == selectedTab,
                                    onClick = { viewModel.selectTab(tab) },
                                    text = { Text(tab.label) },
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when {
                    editingRule != null -> RuleEditorScreen(
                        initial = editingRule!!,
                        events = events,
                        onSave = {
                            viewModel.saveRule(it)
                            editingRule = null
                        },
                        onCancel = { editingRule = null },
                    )

                    showNotifications -> NotificationsScreen(
                        rules = rules,
                        events = events,
                        onAdd = { editingRule = NotificationRule() },
                        onEdit = { editingRule = it },
                        onToggle = { rule, on -> viewModel.saveRule(rule.copy(enabled = on)) },
                        onDelete = viewModel::deleteRule,
                    )

                    else -> TimetableScreen(
                        events = events,
                        enabledFeeds = enabledFeeds,
                        hideMusBlock = hideMusBlock,
                        today = today,
                        tab = selectedTab,
                        animateFromWeekStart = animateWeekScroll,
                        scrollToTodayRequests = scrollToTodayRequests,
                        onToggleFeed = viewModel::toggleFeed,
                        onEventClick = viewModel::showDetails,
                    )
                }
            }
        }
    }

    selectedEvent?.let { selection ->
        EventDetailSheet(
            event = selection.event,
            clash = selection.clash,
            participants = selection.participants,
            participantsLoading = selection.participantsLoading,
            participantsUnavailable = selection.participantsUnavailable,
            onDismiss = viewModel::dismissDetails,
        )
    }
}

/**
 * Opens a site page in the device browser.
 *
 * Deliberately the external browser rather than an in-app WebView: the site needs an
 * NTLM login, and the browser already holds that session, whereas a WebView would
 * prompt for the password all over again.
 */
private fun openInBrowser(context: Context, link: SiteLink) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
    } catch (e: ActivityNotFoundException) {
        // No browser installed; nothing useful to fall back to.
    }
}

/** Offers a crash report to whatever the user wants to send it with. */
private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Streamer crash report")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share crash report"))
    } catch (e: ActivityNotFoundException) {
        // Nothing installed that can share text.
    }
}

/**
 * Opens a system settings screen.
 *
 * The app cannot exempt itself from battery optimisation or grant its own exact-alarm
 * permission; both are the user's to give. All it can do is navigate there, falling
 * back to its own app-info page if the specific screen is unavailable.
 */
private fun openSettings(context: Context, action: String) {
    val intent = Intent(action).apply {
        if (action == Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
        } catch (e2: ActivityNotFoundException) {
            // Nothing sensible left to try.
        }
    }
}

/** "Synced 3 min ago" is more useful at a glance than a timestamp. */
private fun lastSyncedLabel(millis: Long): String {
    if (millis <= 0L) return "Never synced"
    val elapsed = Duration.between(Instant.ofEpochMilli(millis), Instant.now())
    return when {
        elapsed.toMinutes() < 1 -> "Synced just now"
        elapsed.toMinutes() < 60 -> "Synced ${elapsed.toMinutes()} min ago"
        elapsed.toHours() < 24 -> "Synced ${elapsed.toHours()} h ago"
        else -> "Synced ${elapsed.toDays()} d ago"
    }
}
