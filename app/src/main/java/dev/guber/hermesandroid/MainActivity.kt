package dev.guber.hermesandroid

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import dev.guber.hermesandroid.ui.visibleSessions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import dev.guber.hermesandroid.data.ApprovalRequest
import dev.guber.hermesandroid.data.InteractivePrompt
import dev.guber.hermesandroid.data.ChatMessage
import dev.guber.hermesandroid.data.ConnectionStatus
import dev.guber.hermesandroid.data.SessionSummary
import dev.guber.hermesandroid.data.ToolActivity
import dev.guber.hermesandroid.data.reasoningLevels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.guber.hermesandroid.ui.HermesUiState
import dev.guber.hermesandroid.ui.HermesViewModel
import dev.guber.hermesandroid.ui.queueErrorMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = (application as HermesApplication).viewModel
        intent.getStringExtra("session_id")?.let(model::openSession)
        intent.removeExtra("session_id")
        setContent { HermesApp(model) }
        val preferences = getPreferences(Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !preferences.getBoolean("notification_permission_requested", false)) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("session_id")?.let((application as HermesApplication).viewModel::openSession)
        intent.removeExtra("session_id")
    }

    override fun onStart() {
        super.onStart()
        (application as HermesApplication).viewModel.onForeground()
    }
}

@Composable
fun HermesApp(viewModel: HermesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HermesTheme(state.darkMode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).clipToBounds()) {
                if (state.signedIn) ChatShell(state, viewModel) else SetupScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun SetupScreen(state: HermesUiState, viewModel: HermesViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 42.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("H", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
        }
        Spacer(Modifier.height(26.dp))
        Text("Hermes", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("A focused mobile window into your private gateway.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Secure gateway connection", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = state.endpointText,
                    onValueChange = viewModel::setEndpoint,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Gateway URL") },
                    placeholder = { Text("https://your-gateway") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    supportingText = { Text("Use HTTPS/WSS for remote connections") },
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = viewModel::login,
                    enabled = !state.loginInProgress,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (state.loginInProgress) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("Sign in with Hermes", fontWeight = FontWeight.SemiBold)
                }
                if (state.statusText != "Connect to a Hermes Gateway") {
                    Spacer(Modifier.height(12.dp))
                    Text(state.statusText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Hermes uses native PKCE sign-in and keeps access tokens encrypted with Android Keystore. The app never ships a shared API key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatShell(state: HermesUiState, viewModel: HermesViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val closeDrawer: () -> Unit = {
        focusManager.clearFocus(force = true)
        scope.launch { drawerState.close() }
    }
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) focusManager.clearFocus(force = true)
    }
    var showSettings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showSendDefaults by remember { mutableStateOf(false) }
    val chatContext = LocalContext.current
    val exportChat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val text = state.messages.joinToString("\n\n") { "${it.role}:\n${it.text}" }
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { checkNotNull(chatContext.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(text) } }
            }
            android.widget.Toast.makeText(chatContext, if (result.isSuccess) "Chat exported" else "Could not export chat", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                state = state,
                onPin = viewModel::togglePin,
                onClose = closeDrawer,
                onNew = {
                    closeDrawer()
                    viewModel.newSession()
                },
                onSelect = { session ->
                    closeDrawer()
                    viewModel.resumeSession(session)
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            ConnectionPill(state)
                            if (state.currentModel.isNotBlank()) Text(state.currentModel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Sessions")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::refreshActiveSession, enabled = state.status == ConnectionStatus.CONNECTED && state.activeRuntimeSessionId != null) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh conversation")
                        }
                        IconButton(onClick = { showModels = true; viewModel.loadModels() }, enabled = state.status == ConnectionStatus.CONNECTED && !state.isSending) {
                            Icon(Icons.Default.Tune, contentDescription = "Choose model")
                        }
                        Box {
                            IconButton(onClick = { showChatMenu = true }) { Icon(Icons.Default.MoreVert, "Chat actions") }
                            DropdownMenu(expanded = showChatMenu, onDismissRequest = { showChatMenu = false }) {
                                DropdownMenuItem(text = { Text("Default send mode") }, onClick = { showChatMenu = false; showSendDefaults = true })
                                DropdownMenuItem(text = { Text("Refresh chats") }, onClick = { showChatMenu = false; viewModel.refreshSessions() })
                                DropdownMenuItem(text = { Text("Copy transcript") }, enabled = state.messages.isNotEmpty(), onClick = {
                                    showChatMenu = false
                                    copyText(chatContext, state.messages.joinToString("\n\n") { "${it.role}:\n${it.text}" })
                                })
                                DropdownMenuItem(text = { Text("Export chat (.txt)") }, enabled = state.messages.isNotEmpty(), onClick = {
                                    showChatMenu = false; exportChat.launch("hermes-chat-${state.activeSessionId ?: "new"}.txt")
                                })
                            }
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Connection settings") }
                    },
                )
            },
            bottomBar = { Composer(state, viewModel) },
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            Conversation(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
    BackHandler(enabled = drawerState.isOpen, onBack = closeDrawer)
    if (showSendDefaults) AlertDialog(
        onDismissRequest = { showSendDefaults = false }, title = { Text("Default send mode") },
        text = { Column {
            Text("Used by Send while the agent is working. Long-press Send to choose once.")
            TextButton(onClick = { viewModel.setDefaultQueue(false); showSendDefaults = false }) {
                Text("Steer — guide current response")
                if (!state.defaultQueue) Icon(Icons.Default.CheckCircle, "Selected")
            }
            TextButton(onClick = { viewModel.setDefaultQueue(true); showSendDefaults = false }) {
                Text("Queue — send after response")
                if (state.defaultQueue) Icon(Icons.Default.CheckCircle, "Selected")
            }
        } }, confirmButton = { TextButton(onClick = { showSendDefaults = false }) { Text("Close") } },
    )
    if (showSettings) {
        SettingsDialog(state, viewModel, onDismiss = { showSettings = false })
    }
    if (showModels) ModelPicker(state, viewModel, onDismiss = { showModels = false })
    state.modelConfirmation?.let { message ->
        AlertDialog(onDismissRequest = viewModel::cancelModelConfirmation, title = { Text("Confirm model change") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::confirmModel) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = viewModel::cancelModelConfirmation) { Text("Cancel") } })
    }
}

@Composable
private fun ModelPicker(state: HermesUiState, viewModel: HermesViewModel, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var showReasoning by remember { mutableStateOf(false) }
    val models = state.models.filter { "${it.providerName} ${it.id}".contains(search, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose model") },
        text = {
            Column {
                Text("Current: ${state.currentModel.ifBlank { "Gateway default" }}", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { showReasoning = true }, enabled = !state.changingModel && !state.isSending) {
                        Text("Reasoning: ${state.currentReasoning.ifBlank { "Gateway default" }}")
                    }
                    DropdownMenu(expanded = showReasoning, onDismissRequest = { showReasoning = false }) {
                        reasoningLevels.forEach { level ->
                            DropdownMenuItem(text = { Text(level) }, onClick = { showReasoning = false; viewModel.selectReasoning(level) })
                        }
                    }
                }
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search models") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                if (state.loadingModels || state.changingModel) CircularProgressIndicator(Modifier.size(24.dp))
                state.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.fillMaxWidth().height(350.dp)) {
                    items(models, key = { "${it.provider}/${it.id}" }) { model ->
                        TextButton(onClick = { viewModel.selectModel(model) }, modifier = Modifier.fillMaxWidth(),
                            enabled = model.available && !state.changingModel && !state.loadingModels && !state.isSending && state.status == ConnectionStatus.CONNECTED) {
                            Column(Modifier.weight(1f)) {
                                Text(model.id, modifier = Modifier.fillMaxWidth())
                                Text(model.providerName + if (!model.available) " · Unavailable" else "", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall)
                            }
                            if (state.currentModel == model.id && state.currentProvider == model.provider) Icon(Icons.Default.CheckCircle, contentDescription = "Selected")
                        }
                    }
                    if (models.isEmpty() && !state.loadingModels) item { Text("No available models match your search.") }
                }
                Text("Applies to this conversation.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = viewModel::loadModels, enabled = !state.loadingModels) { Text("Refresh") } },
    )
}

@Composable
private fun ConnectionPill(state: HermesUiState) {
    val (color, label) = when (state.status) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary to "Connected"
        ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary to "Connecting"
        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.error to "Needs attention"
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to "Offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun SessionDrawer(state: HermesUiState, onNew: () -> Unit, onSelect: (SessionSummary) -> Unit, onPin: (SessionSummary) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val sessions = visibleSessions(state.sessions, state.pinnedSessions, query)
    ModalDrawerSheet(windowInsets = WindowInsets.safeDrawing) {
        Column(Modifier.fillMaxSize().padding(top = 18.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("H", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hermes", fontWeight = FontWeight.Bold)
                    Text("Sessions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close sessions") }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New conversation")
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search chats") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            if (state.sessions.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No saved sessions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Start a conversation to create one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    if (sessions.isEmpty()) item { Text("No matching chats", Modifier.padding(20.dp)) }
                    items(sessions, key = { it.id }) { session ->
                        SessionRow(session, selected = session.id == state.activeSessionId, pinned = session.id in state.pinnedSessions,
                            onPin = { onPin(session) }, onClick = { onSelect(session) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, pinned: Boolean, onPin: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (session.preview.isNotBlank()) Text(session.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (session.messageCount > 0) Text(session.messageCount.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onPin) {
            Icon(Icons.Default.PushPin, contentDescription = if (pinned) "Unpin ${session.title}" else "Pin ${session.title}",
                tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
    }
}

private sealed interface TimelineItem {
    val key: String
    val order: Long
}

private data class TimelineMessage(val message: ChatMessage) : TimelineItem {
    override val key = message.id
    override val order = message.timelineOrder
}

private data class TimelineTool(val tool: ToolActivity) : TimelineItem {
    override val key = "tool-${tool.id}"
    override val order = tool.timelineOrder
}

private data class TimelineApproval(val approval: ApprovalRequest) : TimelineItem {
    override val key = "approval-${approval.requestId}"
    override val order = approval.timelineOrder
}

private data class TimelinePrompt(val prompt: InteractivePrompt) : TimelineItem {
    override val key = "prompt-${prompt.requestId}"
    override val order = prompt.timelineOrder
}

private fun HermesUiState.timeline(): List<TimelineItem> = buildList {
    messages.forEach { add(TimelineMessage(it)) }
    tools.forEach { add(TimelineTool(it)) }
    approvals.forEach { add(TimelineApproval(it)) }
    prompts.forEach { add(TimelinePrompt(it)) }
}.sortedWith(compareBy<TimelineItem> { it.order }.thenBy { it.key })

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Conversation(state: HermesUiState, viewModel: HermesViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val timeline = state.timeline()
    val itemCount = timeline.size + if (state.attachments.isNotEmpty()) 1 else 0
    val canScrollToLatest by remember { derivedStateOf { listState.canScrollForward } }
    LaunchedEffect(timeline.lastOrNull()?.key, state.messages.lastOrNull()?.text, state.tools.lastOrNull()?.detail) {
        if (itemCount > 0 && !listState.canScrollForward) listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
    }
    if (state.status == ConnectionStatus.ERROR && state.messages.isEmpty()) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(14.dp))
            Text(state.statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Check the server URL and gateway policy, then reconnect from settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (timeline.isEmpty()) {
                item { EmptyConversation(statusText = state.statusText) }
            }
            timeline.forEachIndexed { index, entry ->
                when (entry) {
                    is TimelineMessage -> {
                        val message = entry.message
                        if (message.role.equals("user", ignoreCase = true)) {
                            stickyHeader(key = "sticky-${message.id}") {
                                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                                    MessageBubble(message, onClick = { scope.launch { listState.scrollToItem(index) } }, maxLines = 4, collapsible = true)
                                }
                            }
                        } else {
                            item(key = entry.key) { MessageBubble(message, imageBaseUrl = state.endpointText) }
                        }
                    }
                    is TimelineTool -> item(key = entry.key) { ToolCard(entry.tool) }
                    is TimelineApproval -> item(key = entry.key) { ApprovalCard(entry.approval, viewModel) }
                    is TimelinePrompt -> item(key = entry.key) { PromptCard(entry.prompt, viewModel) }
                }
            }
            if (state.attachments.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        state.attachments.takeLast(3).forEach { attachment ->
                            AssistChip(onClick = {}, label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) })
                        }
                    }
                }
            }
        }
        if (canScrollToLatest) {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { listState.scrollToItem(itemCount - 1, Int.MAX_VALUE) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                icon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to latest message") },
                text = { Text(if (state.awaitingInput) "Input needed" else "Latest") },
            )
        }
    }
}

@Composable
private fun EmptyConversation(statusText: String) {
    Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(70.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text("H", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(18.dp))
        Text("Ready when you are.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(22.dp))
        Text("Messages, tools, and approvals stay in your Hermes Gateway.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ChatMessage, onClick: () -> Unit = {}, maxLines: Int = Int.MAX_VALUE, collapsible: Boolean = false, imageBaseUrl: String = "") {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val user = message.role.equals("user", ignoreCase = true)
    val canExpand = collapsible && message.text.length > 240
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(if (user) 0.88f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (user) "You" else "Hermes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                Text(messageTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                Surface(
                    color = if (user) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (user) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(18.dp, 18.dp, if (user) 5.dp else 18.dp, if (user) 18.dp else 5.dp),
                ) {
                    Column {
                        val clickModifier = if (collapsible) Modifier.clickable(onClick = onClick) else Modifier
                        val textModifier = clickModifier
                            .then(if (canExpand && expanded) Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                        if (user) {
                            SelectionContainer {
                                Text(
                                    text = markdownAnnotatedString(message.text.ifBlank { if (message.isStreaming) "…" else "(empty message)" } + if (message.isStreaming) "  ▌" else ""),
                                    modifier = textModifier,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = if (canExpand && !expanded) maxLines else Int.MAX_VALUE,
                                    overflow = if (canExpand && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
                                )
                            }
                        } else {
                            val content = splitMarkdownImages(message.text)
                            SelectionContainer {
                                Column(textModifier) {
                                    if (content.markdown.isNotBlank()) MarkdownText(content.markdown + if (message.isStreaming) "\n\n▌" else "")
                                    content.imageUrls.forEach { GatewayImage(it, imageBaseUrl) }
                                }
                            }
                        }
                        if (canExpand) Button(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.20f),
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) { Text(if (expanded) "Collapse message" else "Show full message") }
                    }
                }
            }
        }
    }
}

internal data class MarkdownContent(val markdown: String, val imageUrls: List<String>)

private val markdownImagePattern = Regex("""!\[[^]]*]\(([^\s)]+)(?:\s+[\"'][^)]*[\"'])?\)""")

internal fun splitMarkdownImages(text: String): MarkdownContent = MarkdownContent(
    markdown = text.replace(markdownImagePattern, "").trim(),
    imageUrls = markdownImagePattern.findAll(text).map { it.groupValues[1] }.distinct().toList(),
)

@Composable
private fun MarkdownText(markdown: String) {
    Text(markdownAnnotatedString(markdown), style = MaterialTheme.typography.bodyLarge)
}

internal fun markdownAnnotatedString(markdown: String): AnnotatedString = buildAnnotatedString {
    var codeBlock = false
    markdown.lines().forEachIndexed { index, original ->
        if (original.trimStart().startsWith("```")) {
            codeBlock = !codeBlock
        } else {
            val heading = original.trimStart().startsWith("#")
            val line = original.replaceFirst(Regex("""^#{1,6}\s+"""), "").removePrefix("> ")
            if (codeBlock) withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(line) }
            else if (heading) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendMarkdownInline(line) }
            else appendMarkdownInline(line)
            if (index < markdown.lines().lastIndex) append('\n')
        }
    }
}

private val markdownToken = Regex("""(\*\*[^*]+\*\*|`[^`]+`|(?<!\*)\*[^*]+\*(?!\*)|\[[^]]+]\([^)]*\))""")

private fun AnnotatedString.Builder.appendMarkdownInline(line: String) {
    var cursor = 0
    markdownToken.findAll(line).forEach { match ->
        append(line.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.drop(2).dropLast(2)) }
            token.startsWith('`') -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(token.drop(1).dropLast(1)) }
            token.startsWith('*') -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.drop(1).dropLast(1)) }
            else -> append(token.substringAfter('[').substringBefore("]("))
        }
        cursor = match.range.last + 1
    }
    append(line.substring(cursor))
}

internal fun gatewayImageUrl(source: String, endpoint: String): String? {
    if (source.startsWith("data:image/", ignoreCase = true)) return source
    return runCatching {
        val base = URI(endpoint)
        if (base.scheme != "https" || base.host.isNullOrBlank()) return null
        val target = if (source.startsWith('/')) base.resolve(source) else URI(source)
        target.takeIf { it.scheme == "https" && it.host == base.host }?.toString()
    }.getOrNull()
}

@Composable
private fun GatewayImage(source: String, endpoint: String) {
    val url = gatewayImageUrl(source, endpoint) ?: return
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { bitmap = withContext(Dispatchers.IO) { loadGatewayImage(url) } }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(), contentDescription = "Message image", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(top = 8.dp),
        )
    }
}

private fun loadGatewayImage(url: String): Bitmap? = runCatching {
    val bytes = if (url.startsWith("data:image/", ignoreCase = true)) Base64.decode(url.substringAfter(','), Base64.DEFAULT) else {
        URL(url).openStream().use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 25 * 1024 * 1024) throw IOException("Image is too large")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

internal fun messageTime(value: String): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    return runCatching {
        val timestamp = value.trim().takeUnless { it.equals("null", ignoreCase = true) || it.equals("undefined", ignoreCase = true) }
        val instant = timestamp?.toLongOrNull()?.let { if (it < 10_000_000_000L) Instant.ofEpochSecond(it) else Instant.ofEpochMilli(it) }
            ?: timestamp?.let(Instant::parse) ?: Instant.now()
        formatter.format(instant)
    }.getOrElse { formatter.format(Instant.now()) }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(android.content.ClipboardManager::class.java)
        .setPrimaryClip(android.content.ClipData.newPlainText("Hermes", text))
    if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun ToolCard(tool: ToolActivity) {
    var expanded by remember(tool.id) { mutableStateOf(true) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(13.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                    Text("⌁", color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tool.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(7.dp))
                        Text(if (tool.complete) "done" else "running", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded && tool.detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(tool.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ApprovalCard(request: ApprovalRequest, viewModel: HermesViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(9.dp))
                Text("Approval required", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(request.command, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (request.resolved) {
                Text("Answered: ${request.selectedChoice.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    request.choices.forEach { choice ->
                        Button(
                            onClick = { viewModel.chooseApproval(request, choice) },
                            enabled = !request.submitting,
                            colors = ButtonDefaults.buttonColors(containerColor = if (choice == "deny") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                        ) { Text(choice.replaceFirstChar { it.uppercase() }) }
                    }
                }
                if (request.submitting) Text("Sending…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                request.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(5.dp))
            Text("Unanswered approvals remain server-side; cancel defaults to deny.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun PromptCard(request: InteractivePrompt, viewModel: HermesViewModel) {
    var answer by rememberSaveable(request.requestId) { mutableStateOf("") }
    val secret = request.type in setOf("sudo.request", "secret.request")
    val terminalRead = request.type == "terminal.read.request"
    val title = when (request.type) {
        "clarify.request" -> "Your answer is needed"
        "sudo.request" -> "Administrator password required"
        "secret.request" -> "Secret required"
        else -> "Desktop terminal required"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
            Text(request.question, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
            if (request.resolved) {
                Text(request.responseSummary.orEmpty(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            } else {
                request.choices.forEach { choice ->
                    OutlinedButton(onClick = { viewModel.respondPrompt(request, choice) }, enabled = !request.submitting, modifier = Modifier.fillMaxWidth()) {
                        Text(choice, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (!terminalRead) {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        enabled = !request.submitting,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (request.choices.isEmpty()) "Response" else "Other response") },
                        singleLine = true,
                        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
                    )
                } else {
                    Text("Skip to let Hermes continue without terminal output.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.respondPrompt(request, "") }, enabled = !request.submitting) { Text("Skip") }
                    if (!terminalRead) {
                        Button(onClick = { viewModel.respondPrompt(request, answer) }, enabled = answer.isNotBlank() && !request.submitting) { Text("Send") }
                    }
                }
                if (request.submitting) Text("Sending…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                request.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(state: HermesUiState, viewModel: HermesViewModel) {
    val context = LocalContext.current
    var showSendOptions by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = readAttachment(context, uri)
            val mime = context.contentResolver.getType(uri).orEmpty()
            viewModel.attach(displayName(context, uri), mime, bytes)
        }.onFailure { viewModel.reportError(it.message ?: "Could not read attachment") }
    }
    Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 9.dp)) {
            val queued = state.queuedPrompts.filter { it.sessionId == state.activeSessionId }
            if (queued.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(queued, key = { it.id }) { item ->
                    val sending = state.sendingQueuedId == item.id
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(if (sending) "SENDING…" else if (item.error != null) "NOT SENT" else "QUEUED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(item.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            item.error?.let { Text(queueErrorMessage(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            Row {
                                TextButton(onClick = { viewModel.cancelQueued(item.id) }, enabled = !sending) { Text("Cancel") }
                                TextButton(onClick = { viewModel.sendQueuedNow(item.id) }, enabled = !state.sendingFollowUp && item.runtimeId != null && state.status == ConnectionStatus.CONNECTED) { Text(if (item.error == null) "Send now" else "Retry") }
                            }
                        }
                    }
                }
            }
            if (state.statusText.isNotBlank() && state.statusText != "Connected") {
                Text(state.statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = state.activeRuntimeSessionId != null && state.status == ConnectionStatus.CONNECTED) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach image or file")
                }
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = viewModel::updateDraft,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Hermes…") },
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (state.isSending && !state.awaitingInput) {
                    IconButton(
                        onClick = viewModel::stopStreaming,
                        enabled = state.status == ConnectionStatus.CONNECTED,
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Stop streaming", tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Box {
                    Box(Modifier.size(48.dp).clip(CircleShape)
                        .background(if (state.draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .combinedClickable(
                            enabled = !state.sendingFollowUp && !state.changingModel,
                            role = Role.Button, onLongClickLabel = "Send options",
                            onClick = { if (state.draft.isBlank()) showSendOptions = true else viewModel.submitPrompt() },
                            onLongClick = { showSendOptions = true }), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (state.draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showSendOptions, onDismissRequest = { showSendOptions = false }) {
                        val canSend = state.draft.isNotBlank() && state.activeRuntimeSessionId != null && state.status == ConnectionStatus.CONNECTED && !state.sendingFollowUp
                        DropdownMenuItem(text = { Text(if (state.defaultQueue) "Steer" else "Steer (default)") }, enabled = canSend, onClick = { showSendOptions = false; viewModel.sendFollowUp(false) })
                        DropdownMenuItem(text = { Text(if (state.defaultQueue) "Queue (default)" else "Queue") }, enabled = canSend, onClick = { showSendOptions = false; viewModel.sendFollowUp(true) })
                    }
                }
            }
            Text("Files are staged by the gateway; Android paths are never sent as if they were host paths.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, top = 4.dp))
        }
    }
}

@Composable
private fun SettingsDialog(state: HermesUiState, viewModel: HermesViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
        title = { Text("Connection settings") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.darkMode) "Dark mode" else "Light mode", modifier = Modifier.weight(1f))
                    Switch(checked = state.darkMode, onCheckedChange = viewModel::setDarkMode, modifier = Modifier.semantics { contentDescription = "Dark mode" })
                }
                Text(state.endpointText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Your sign-in is stored securely on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
                if (state.statusText.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(state.statusText, style = MaterialTheme.typography.labelMedium, color = if (state.status == ConnectionStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.connectSaved(); onDismiss() }) { Text("Reconnect") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { viewModel.disconnect(); onDismiss() }) { Text("Disconnect") }
                TextButton(onClick = { viewModel.signOut(); onDismiss() }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

private fun readAttachment(context: Context, uri: Uri): ByteArray {
    val maxBytes = 25L * 1024L * 1024L
    val output = ByteArrayOutputStream()
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IllegalArgumentException("Attachments are limited to 25 MiB")
            output.write(buffer, 0, read)
        }
    } ?: throw IllegalArgumentException("Could not open the selected file")
    return output.toByteArray()
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0).orEmpty().ifBlank { "attachment" }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "attachment" } ?: "attachment"
}

private val HermesDark = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFF9A91FF),
    onPrimary = Color(0xFF17122F),
    primaryContainer = Color(0xFF5145A5),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondaryContainer = Color(0xFF343150),
    surface = Color(0xFF101116),
    surfaceContainer = Color(0xFF1A1B23),
    surfaceContainerHigh = Color(0xFF24252F),
    surfaceContainerHighest = Color(0xFF30313C),
    background = Color(0xFF101116),
    onSurface = Color(0xFFE7E1EA),
    onSurfaceVariant = Color(0xFFC7C0CB),
    outline = Color(0xFF918A98),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5D1517),
    onErrorContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFF83D5C7),
    tertiaryContainer = Color(0xFF1C4D48),
    onTertiaryContainer = Color(0xFFA1F2E2),
)

@Composable
private fun HermesTheme(darkMode: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as android.app.Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkMode
            isAppearanceLightNavigationBars = !darkMode
        }
    }
    MaterialTheme(colorScheme = if (darkMode) HermesDark else HermesLight, content = content)
}

private val HermesLight = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF5E50B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E0FF),
    onPrimaryContainer = Color(0xFF17122F),
    secondaryContainer = Color(0xFFE7E1F8),
    surface = Color(0xFFFCF8FF),
    background = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B20),
    onSurfaceVariant = Color(0xFF49454F),
)
