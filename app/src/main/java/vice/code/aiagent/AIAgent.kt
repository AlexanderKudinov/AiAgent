package vice.code.aiagent

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.type.content
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class AIAgent(
    private val context: Context, // Добавлен Context
    private val apiKey: String,
    private val modelName: String = "gemini-1.5-flash"
) {
    private val generativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey
    )

    private var chat: Chat

    private val sharedPreferences = context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
    private val HISTORY_KEY = "messages_history"

    init {
        // Инициализация чата с загруженной историей
        val loadedMessages = loadChatHistory()
        val initialHistoryForLLM = loadedMessages.map { message ->
            content(role = if (message.isUser) "user" else "model") {
                text(message.text)
            }
        }
        chat = generativeModel.startChat(history = initialHistoryForLLM.toMutableList())
    }

    /**
     * Отправляет сообщение в LLM и возвращает ответ.
     * Также добавляет сообщение в историю LLM.
     * @param text текст сообщения пользователя
     * @return ответ от LLM или сообщение об ошибке
     */
    suspend fun sendMessage(text: String): String {
        return try {
            val response = chat.sendMessage(text) // Используем chat.sendMessage
            response.text ?: "Пустой ответ от модели"
        } catch (e: Exception) {
            "Ошибка: ${e.localizedMessage ?: "неизвестная ошибка"}"
        }
    }

    /**
     * Сохраняет историю диалога в SharedPreferences.
     * @param messages список сообщений для сохранения
     */
    fun saveChatHistory(messages: List<ChatMessage>) {
        val jsonString = Json.encodeToString(messages)
        sharedPreferences.edit().putString(HISTORY_KEY, jsonString).apply()
    }

    /**
     * Загружает историю диалога из SharedPreferences.
     * @return список загруженных сообщений или пустой список, если история не найдена
     */
    fun loadChatHistory(): List<ChatMessage> {
        val jsonString = sharedPreferences.getString(HISTORY_KEY, null)
        return if (jsonString != null) {
            try {
                Json.decodeFromString<List<ChatMessage>>(jsonString)
            } catch (e: Exception) {
                // В случае ошибки десериализации, возвращаем пустой список и логируем ошибку
                // (в реальном приложении здесь можно добавить Log.e)
                emptyList()
            }
        } else {
            emptyList()
        }
    }
}