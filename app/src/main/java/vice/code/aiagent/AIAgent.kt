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

/**
 * Класс, представляющий результат отправки сообщения с метаданными токенов.
 */
data class AgentResponse(
    val text: String,
    val requestTokens: Int = 0,
    val responseTokens: Int = 0,
    val totalHistoryTokens: Int = 0
)

class AIAgent(
    private val context: Context,
    private val apiKey: String,
    private val modelName: String
) {
    private val generativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey
    )

    private var chat: Chat

    private val sharedPreferences = context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
    private val HISTORY_KEY = "messages_history"

    init {
        val loadedMessages = loadChatHistory()
        val initialHistoryForLLM = loadedMessages.map { message ->
            content(role = if (message.isUser) "user" else "model") {
                text(message.text)
            }
        }
        chat = generativeModel.startChat(history = initialHistoryForLLM.toMutableList())
    }

    /**
     * Отправляет сообщение в LLM и возвращает ответ вместе со статистикой токенов.
     */
    suspend fun sendMessage(text: String): AgentResponse {
        return try {
            // 1. Подсчет токенов для текущего запроса
            val requestTokens = countTokens(text)

            // Отправка сообщения
            val response = chat.sendMessage(text)
            val responseText = response.text ?: "Пустой ответ от модели"

            // 2. Подсчет токенов для ответа модели
            val responseTokens = countTokens(responseText)

            // 3. Подсчет токенов для всей истории (включая только что отправленное)
            val totalHistoryTokens = countHistoryTokens()

            Log.d("AIAgent", "Tokens - Req: $requestTokens, Res: $responseTokens, Total: $totalHistoryTokens")

            AgentResponse(
                text = responseText,
                requestTokens = requestTokens,
                responseTokens = responseTokens,
                totalHistoryTokens = totalHistoryTokens
            )
        } catch (e: Exception) {
            AgentResponse(text = "Ошибка: ${e.localizedMessage ?: "неизвестная ошибка"}")
        }
    }

    /**
     * Подсчитывает токены для произвольного текста.
     */
    suspend fun countTokens(text: String): Int {
        return try {
            val response = generativeModel.countTokens(content { text(text) })
            response.totalTokens
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Подсчитывает токены для всей текущей истории диалога.
     */
    suspend fun countHistoryTokens(): Int {
        return try {
            // Преобразуем историю чата в массив Content для метода countTokens
            val historyContent = chat.history
            val response = generativeModel.countTokens(*historyContent.toTypedArray())
            response.totalTokens
        } catch (e: Exception) {
            0
        }
    }

    fun saveChatHistory(messages: List<ChatMessage>) {
        val jsonString = Json.encodeToString(messages)
        sharedPreferences.edit { putString(HISTORY_KEY, jsonString) }
    }

    fun loadChatHistory(): List<ChatMessage> {
        val jsonString = sharedPreferences.getString(HISTORY_KEY, null)
        return if (jsonString != null) {
            try {
                Json.decodeFromString<List<ChatMessage>>(jsonString)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
}