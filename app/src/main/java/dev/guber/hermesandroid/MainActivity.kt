package dev.guber.hermesandroid

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import dev.guber.hermesandroid.data.ApprovalRequest
import dev.guber.hermesandroid.data.ChatMessage
import dev.guber.hermesandroid.data.ConnectionStatus
import dev.guber.hermesandroid.data.SessionSummary
import dev.guber.hermesandroid.data.ToolActivity
import dev.guber.hermesandroid.ui.HermesUiState
import dev.guber.hermesandroid.ui.HermesViewModel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    HermesTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.signedIn) ChatShell(state, viewModel) else SetupScreen(state, viewModel)
        }
    }
}

@Composable
private fun SetupScreen(state: HermesUiState, viewModel: HermesViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
    var showSettings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                state = state,
                onNew = {
                    scope.launch { drawerState.close() }
                    viewModel.newSession()
                },
                onSelect = { session ->
                    scope.launch { drawerState.close() }
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
                        IconButton(onClick = { showModels = true; viewModel.loadModels() }, enabled = state.status == ConnectionStatus.CONNECTED && !state.isSending) {
                            Icon(Icons.Default.Tune, contentDescription = "Choose model")
                        }
                        IconButton(onClick = { viewModel.refreshSessions() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh sessions") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Connection settings") }
                    },
                )
            },
            bottomBar = { Composer(state, viewModel) },
            contentWindowInsets = WindowInsets.navigationBars,
        ) { padding ->
            Conversation(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
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
    val models = state.models.filter { "${it.providerName} ${it.id}".contains(search, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose model") },
        text = {
            Column {
                Text("Current: ${state.currentModel.ifBlank { "Gateway default" }}", style = MaterialTheme.typography.bodySmall)
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
private fun SessionDrawer(state: HermesUiState, onNew: () -> Unit, onSelect: (SessionSummary) -> Unit) {
    ModalDrawerSheet {
        Column(Modifier.fillMaxSize().padding(top = 18.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("H", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Hermes", fontWeight = FontWeight.Bold)
                    Text("Sessions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            if (state.sessions.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No saved sessions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Start a conversation to create one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionRow(session, selected = session.id == state.activeSessionId, onClick = { onSelect(session) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, onClick: () -> Unit) {
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
    }
}

@Composable
private fun Conversation(state: HermesUiState, viewModel: HermesViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.tools.size, state.approvals.size) {
        val count = state.messages.size + state.tools.size + state.approvals.size
        if (count > 0) listState.animateScrollToItem(count - 1)
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
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.messages.isEmpty() && state.tools.isEmpty()) {
            item { EmptyConversation(statusText = state.statusText) }
        }
        items(state.tools, key = { "tool-${it.id}" }) { ToolCard(it) }
        items(state.approvals, key = { "approval-${it.requestId}" }) { ApprovalCard(it, viewModel) }
        items(state.messages, key = { it.id }) { MessageBubble(it) }
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

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role.equals("user", ignoreCase = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(if (user) 0.88f else 1f)) {
            Text(if (user) "You" else "Hermes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            Surface(
                color = if (user) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (user) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(18.dp, 18.dp, if (user) 5.dp else 18.dp, if (user) 18.dp else 5.dp),
            ) {
                Text(
                    text = message.text.ifBlank { if (message.isStreaming) "…" else "(empty message)" } + if (message.isStreaming) "  ▌" else "",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolActivity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
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
                if (tool.detail.isNotBlank()) Text(tool.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                request.choices.forEach { choice ->
                    Button(
                        onClick = { viewModel.chooseApproval(request, choice) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (choice == "deny") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                    ) { Text(choice.replaceFirstChar { it.uppercase() }) }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text("Unanswered approvals remain server-side; cancel defaults to deny.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun Composer(state: HermesUiState, viewModel: HermesViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = readAttachment(context, uri)
            val mime = context.contentResolver.getType(uri).orEmpty()
            viewModel.attach(displayName(context, uri), mime, bytes)
        }.onFailure { viewModel.reportError(it.message ?: "Could not read attachment") }
    }
    Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 9.dp)) {
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
                if (state.isSending) {
                    IconButton(
                        onClick = viewModel::stopStreaming,
                        enabled = state.status == ConnectionStatus.CONNECTED,
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Stop streaming", tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                } else {
                    IconButton(
                        onClick = viewModel::submitPrompt,
                        enabled = state.draft.isNotBlank() && state.status == ConnectionStatus.CONNECTED && !state.changingModel,
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(if (state.draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (state.draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HermesDark, content = content)
}
