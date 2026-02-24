package vice.code.aiagent

import com.google.ai.client.generativeai.GenerativeModel

class AIAgent(
    private val apiKey: String,
    private val modelName: String,
) {
    private val generativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey
    )

    suspend fun sendMessage(text: String): String {
        return try {
            val response = generativeModel.generateContent(text)
            response.text ?: "Пустой ответ от модели"
        } catch (e: Exception) {
            "Ошибка: ${e.localizedMessage ?: "неизвестная ошибка"}"
        }
    }
}