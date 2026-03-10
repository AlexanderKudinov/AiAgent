package vice.code.aiagent

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException

@Serializable
data class MCPTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject? = null
)

@Serializable
data class MCPListToolsResult(
    val tools: List<MCPTool>
)

@Serializable
data class MCPRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
)

@Serializable
data class MCPResponse(
    val jsonrpc: String,
    val id: Int,
    val result: MCPListToolsResult? = null,
    val error: MCPError? = null
)

@Serializable
data class MCPError(
    val code: Int,
    val message: String
)

class MCPClient(private val serverUrl: String = "http://10.0.2.2:8080") {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listTools(): List<MCPTool> {
        val requestBody = MCPRequest(
            id = 1,
            method = "tools/list"
        )
        val jsonBody = json.encodeToString(requestBody)
        val request = Request.Builder()
            .url("$serverUrl/mcp")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return suspendCoroutine { continuation ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            continuation.resumeWithException(IOException("Unexpected code $response"))
                        } else {
                            val body = response.body?.string()
                            try {
                                val mcpResponse = json.decodeFromString<MCPResponse>(body ?: "")
                                if (mcpResponse.error != null) {
                                    continuation.resumeWithException(IOException("MCP error: ${mcpResponse.error.message}"))
                                } else {
                                    val tools = mcpResponse.result?.tools ?: emptyList()
                                    continuation.resume(tools)
                                }
                            } catch (e: Exception) {
                                continuation.resumeWithException(e)
                            }
                        }
                    }
                }
            })
        }
    }
}