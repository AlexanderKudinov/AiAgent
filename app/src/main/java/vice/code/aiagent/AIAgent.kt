package vice.code.aiagent

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.type.content
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import androidx.core.content.edit
import androidx.compose.runtime.*

enum class ChatStrategy {
    SLIDING_WINDOW,
    STICKY_FACTS,
    BRANCHING
}

enum class TaskState {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE
}

data class AgentResponse(
    val text: String,
    val requestTokens: Int = 0,
    val responseTokens: Int = 0,
    val totalHistoryTokens: Int = 0
)

class AIAgent(
    private val context: Context,
    private val apiKey: String,
    private val modelName: String = "gemini-2.5-flash"
) {
    // Состояние стратегий, профиля и задачи
    var currentStrategy by mutableStateOf(ChatStrategy.SLIDING_WINDOW)
        private set
        
    var facts by mutableStateOf("")
        private set
        
    var activeBranch by mutableStateOf("main")
        private set

    // Свойство userProfile имеет автоматический сеттер. Мы делаем его приватным,
    // чтобы не конфликтовать с нашей ручной функцией updateUserProfile.
    var userProfile by mutableStateOf("")
        private set
        
    var currentTaskState by mutableStateOf(TaskState.PLANNING)
        private set
        
    var currentStep by mutableStateOf("1. Analyzing request.")
        private set
    
    private val sharedPreferences = context.getSharedPreferences("ai_agent_prefs", Context.MODE_PRIVATE)
    
    private val HISTORY_PREFIX = "history_"
    private val FACTS_KEY = "chat_facts"
    private val STRATEGY_KEY = "current_strategy"
    private val BRANCHES_LIST_KEY = "branches_list"
    private val PROFILE_KEY = "user_profile"
    private val TASK_STATE_KEY = "task_state"
    private val TASK_STEP_KEY = "task_step"

    private val MAX_WINDOW_SIZE = 10

    private var generativeModel: GenerativeModel
    private lateinit var chat: Chat

    init {
        // Загрузка состояния
        val savedStrategy = sharedPreferences.getString(STRATEGY_KEY, ChatStrategy.SLIDING_WINDOW.name)!!
        currentStrategy = ChatStrategy.valueOf(savedStrategy)
        facts = sharedPreferences.getString(FACTS_KEY, "") ?: ""
        activeBranch = sharedPreferences.getString("active_branch", "main") ?: "main"
        userProfile = sharedPreferences.getString(PROFILE_KEY, "Ты полезный ассистент. Отвечай кратко и по делу.") ?: ""
        
        // Загрузка состояния задачи
        currentTaskState = TaskState.valueOf(sharedPreferences.getString(TASK_STATE_KEY, TaskState.PLANNING.name)!!)
        currentStep = sharedPreferences.getString(TASK_STEP_KEY, "1. Analyzing request.") ?: "1. Analyzing request."
        
        // Инициализация модели с системной инструкцией (профилем)
        generativeModel = createModel()
        initializeChat()
    }

    private fun createModel(): GenerativeModel {
        return GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            systemInstruction = content { text(userProfile) }
        )
    }

    fun initializeChat() {
        val history = loadChatHistory(activeBranch)
        val historyForLLM = mutableListOf<com.google.ai.client.generativeai.type.Content>()

        // Добавляем информацию о текущем состоянии задачи в начало контекста, если это не DONE
        if (currentTaskState != TaskState.DONE) {
             historyForLLM.add(content(role = "user") { text("Текущий этап задачи: ${currentTaskState.name}, Шаг: $currentStep. Продолжай работу.") })
             historyForLLM.add(content(role = "model") { text("Принято. Продолжаю выполнение этапа ${currentTaskState.name}.") })
        }

        when (currentStrategy) {
            ChatStrategy.SLIDING_WINDOW -> {
                history.takeLast(MAX_WINDOW_SIZE).forEach { msg ->
                    historyForLLM.add(content(role = if (msg.isUser) "user" else "model") { text(msg.text) })
                }
            }
            ChatStrategy.STICKY_FACTS -> {
                if (facts.isNotEmpty()) {
                    historyForLLM.add(content(role = "user") { text("Дополнительные факты о пользователе: $facts") })
                    historyForLLM.add(content(role = "model") { text("Понял. Я буду учитывать эти факты вместе с твоим профилем.") })
                }
                history.takeLast(MAX_WINDOW_SIZE).forEach { msg ->
                    historyForLLM.add(content(role = if (msg.isUser) "user" else "model") { text(msg.text) })
                }
            }
            ChatStrategy.BRANCHING -> {
                history.forEach { msg ->
                    historyForLLM.add(content(role = if (msg.isUser) "user" else "model") { text(msg.text) })
                }
            }
        }
        chat = generativeModel.startChat(history = historyForLLM)
    }

    suspend fun sendMessage(text: String): AgentResponse {
        return try {
            val response = chat.sendMessage(text)
            val responseText = response.text ?: ""
            
            if (currentStrategy == ChatStrategy.STICKY_FACTS) {
                updateFacts(text, responseText)
            }

            AgentResponse(
                text = responseText,
                requestTokens = countTokens(text),
                responseTokens = countTokens(responseText),
                totalHistoryTokens = countHistoryTokens()
            )
        } catch (e: Exception) {
            Log.e("AIAgent", "sendMessage failed", e)
            AgentResponse(text = "Ошибка: ${e.localizedMessage ?: "Неизвестная ошибка API"}")
        }
    }

    private suspend fun updateFacts(userText: String, aiText: String) {
        val prompt = """
            На основе следующего диалога обнови список ключевых фактов.
            Текущие факты: $facts
            Новый диалог:
            Пользователь: $userText
            Ассистент: $aiText
            
            Верни только обновленный список фактов одним абзацем.
        """.trimIndent()
        try {
            val res = generativeModel.generateContent(prompt)
            val newFacts = res.text ?: facts
            if (newFacts != facts) {
                facts = newFacts
                sharedPreferences.edit { putString(FACTS_KEY, facts) }
            }
        } catch (e: Exception) {
            Log.e("AIAgent", "Fact extraction failed", e)
        }
    }

    fun setStrategy(strategy: ChatStrategy) {
        currentStrategy = strategy
        sharedPreferences.edit { putString(STRATEGY_KEY, strategy.name) }
        initializeChat()
    }

    // ИСПОЛЬЗУЕМ ЭТУ ФУНКЦИЮ ДЛЯ УСТАНОВКИ ПРОФИЛЯ
    fun updateUserProfile(profile: String) {
        userProfile = profile
        sharedPreferences.edit { putString(PROFILE_KEY, profile) }
        // При смене профиля нужно пересоздать модель и чат, так как systemInstruction задается при создании
        generativeModel = createModel()
        initializeChat()
    }
    
    fun transitionTo(newState: TaskState, newStep: String? = null) {
        currentTaskState = newState
        sharedPreferences.edit { putString(TASK_STATE_KEY, newState.name) }
        
        if (newStep != null) {
            currentStep = newStep
            sharedPreferences.edit { putString(TASK_STEP_KEY, newStep) }
        }
        
        // При смене состояния или шага нужно перезапустить чат с новым системным контекстом
        initializeChat()
    }

    fun createBranch(name: String) {
        val currentHistory = loadChatHistory(activeBranch)
        saveChatHistory(name, currentHistory)
        
        val branches = getBranches().toMutableSet()
        branches.add(name)
        sharedPreferences.edit { putStringSet(BRANCHES_LIST_KEY, branches) }
        
        switchBranch(name)
    }

    fun switchBranch(name: String) {
        activeBranch = name
        sharedPreferences.edit { putString("active_branch", name) }
        initializeChat()
    }

    fun getBranches(): List<String> {
        return sharedPreferences.getStringSet(BRANCHES_LIST_KEY, setOf("main"))?.toList() ?: listOf("main")
    }

    suspend fun countTokens(text: String): Int {
        if (text.isBlank()) return 0
        return try {
            generativeModel.countTokens(text).totalTokens
        } catch (e: Exception) {
            0
        }
    }

    suspend fun countHistoryTokens(): Int {
        val history = chat.history
        if (history.isEmpty()) return 0
        return try {
            generativeModel.countTokens(*history.toTypedArray()).totalTokens
        } catch (e: Exception) {
            0
        }
    }

    fun saveChatHistory(branch: String, messages: List<ChatMessage>) {
        val jsonString = Json.encodeToString(messages)
        sharedPreferences.edit { putString(HISTORY_PREFIX + branch, jsonString) }
    }

    fun loadChatHistory(branch: String): List<ChatMessage> {
        val jsonString = sharedPreferences.getString(HISTORY_PREFIX + branch, null)
        return if (jsonString != null) {
            try {
                Json.decodeFromString(jsonString)
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()
    }
}