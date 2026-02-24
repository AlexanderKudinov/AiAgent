package vice.code.aiagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vice.code.aiagent.ui.theme.AIAgentTheme

data class ChatMessage(val text: String, val isUser: Boolean)

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
    val apiKey = ""
    val agent = remember { AIAgent(apiKey = apiKey, modelName = "gemini-2.5-flash") }

    // Состояния UI
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputMessage by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        // Список сообщений
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                MessageItem(message = message)
            }
            if (isLoading) {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                }
            }
        }

        // Поле ввода и кнопка отправки
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputMessage,
                onValueChange = { inputMessage = it },
                label = { Text("Введите сообщение") },
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            )
            Button(
                onClick = {
                    val userText = inputMessage.trim()
                    if (userText.isNotBlank()) {
                        // Добавляем сообщение пользователя
                        messages.add(ChatMessage(text = userText, isUser = true))
                        inputMessage = ""
                        isLoading = true

                        coroutineScope.launch {
                            // Вызов агента (инкапсулированная логика)
                            val responseText = agent.sendMessage(userText)
                            messages.add(ChatMessage(text = responseText, isUser = false))
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !isLoading && inputMessage.isNotBlank()
            ) {
                Text("Отправить")
            }
        }
    }
}

@Composable
fun MessageItem(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = if (message.isUser) "Вы" else "Ассистент",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
        Text(
            text = message.text,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ChatScreenPreview() {
    AIAgentTheme {
        ChatScreen()
    }
}