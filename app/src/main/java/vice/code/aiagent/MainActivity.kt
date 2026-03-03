package vice.code.aiagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import vice.code.aiagent.ui.theme.AIAgentTheme

@Serializable
data class ChatMessage(val text: String, val isUser: Boolean, val tokens: Int = 0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIAgentTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ChatScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apiKey = ""
    val agent = remember { AIAgent(context = context, apiKey = apiKey) }

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputMessage by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var totalHistoryTokens by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    var showBranchDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    
    var showProfileDialog by remember { mutableStateOf(false) }
    var editedProfile by remember { mutableStateOf("") }

    // Синхронизация UI
    fun refreshChat() {
        messages.clear()
        messages.addAll(agent.loadChatHistory(agent.activeBranch))
        coroutineScope.launch {
            totalHistoryTokens = agent.countHistoryTokens()
        }
    }

    LaunchedEffect(Unit) {
        refreshChat()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Селектор стратегий
        TabRow(selectedTabIndex = agent.currentStrategy.ordinal) {
            ChatStrategy.entries.forEach { strategy ->
                Tab(
                    selected = agent.currentStrategy == strategy,
                    onClick = { 
                        agent.setStrategy(strategy)
                        refreshChat()
                    },
                    text = { Text(strategy.name.replace("_", " "), fontSize = 10.sp) }
                )
            }
        }

        // 2. Инфо-панель
        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Tokens: $totalHistoryTokens | Branch: ${agent.activeBranch}", style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { 
                    editedProfile = agent.userProfile
                    showProfileDialog = true 
                }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Profile", modifier = Modifier.size(16.dp))
                }
            }
            
            if (agent.currentStrategy == ChatStrategy.STICKY_FACTS && agent.facts.isNotEmpty()) {
                Text("Facts: ${agent.facts}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }

            if (agent.currentStrategy == ChatStrategy.BRANCHING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Branches: ", style = MaterialTheme.typography.labelSmall)
                    agent.getBranches().forEach { branch ->
                        SuggestionChip(
                            onClick = { 
                                agent.switchBranch(branch)
                                refreshChat()
                            },
                            label = { Text(branch) },
                            modifier = Modifier.padding(horizontal = 2.dp),
                            border = if (agent.activeBranch == branch) SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = Color.Blue) else SuggestionChipDefaults.suggestionChipBorder(enabled = true)
                        )
                    }
                    IconButton(onClick = { showBranchDialog = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "New Branch", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                MessageItem(message = message)
            }
            if (isLoading) {
                item { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = inputMessage,
                onValueChange = { inputMessage = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            )
            Button(
                onClick = {
                    val userText = inputMessage.trim()
                    if (userText.isNotBlank()) {
                        coroutineScope.launch {
                            val reqTokens = agent.countTokens(userText)
                            messages.add(ChatMessage(userText, true, reqTokens))
                            inputMessage = ""
                            isLoading = true
                            agent.saveChatHistory(agent.activeBranch, messages)

                            val response = agent.sendMessage(userText)
                            messages.add(ChatMessage(response.text, false, response.responseTokens))
                            totalHistoryTokens = response.totalHistoryTokens
                            
                            agent.saveChatHistory(agent.activeBranch, messages)
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !isLoading && inputMessage.isNotBlank()
            ) {
                Text("Send")
            }
        }
    }

    // Диалог профиля
    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("Профиль и предпочтения") },
            text = {
                OutlinedTextField(
                    value = editedProfile,
                    onValueChange = { editedProfile = it },
                    label = { Text("Стиль, формат, ограничения") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = {
                Button(onClick = {
                    agent.setUserProfile(editedProfile)
                    showProfileDialog = false
                    refreshChat()
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showProfileDialog = false }) { Text("Отмена") }
            }
        )
    }

    // Диалог создания ветки
    if (showBranchDialog) {
        AlertDialog(
            onDismissRequest = { showBranchDialog = false },
            title = { Text("Create Branch") },
            text = {
                OutlinedTextField(value = newBranchName, onValueChange = { newBranchName = it }, label = { Text("Branch Name") })
            },
            confirmButton = {
                Button(onClick = {
                    if (newBranchName.isNotBlank()) {
                        agent.createBranch(newBranchName)
                        refreshChat()
                        showBranchDialog = false
                        newBranchName = ""
                    }
                }) { Text("Create") }
            }
        )
    }
}

@Composable
fun MessageItem(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = if (message.isUser) "You (${message.tokens})" else "AI (${message.tokens})",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
        Text(text = message.text, modifier = Modifier.padding(vertical = 4.dp))
    }
}