import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Anthropic API request and response models
@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String
)

@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 4096
)

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null
)

@Serializable
data class AnthropicMessageResponse(
    val role: String,
    val content: List<ContentBlock>
)

@Serializable
data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val model: String,
    val content: List<ContentBlock>
)

class AnthropicClient(private val apiKey: String) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    private val messages = mutableListOf<AnthropicMessage>()

    suspend fun sendMessage(message: String): String {
        // Add user message to history
        messages.add(AnthropicMessage("user", message))

        // Create the request body
        val requestBody = AnthropicRequest(
            model = "claude-3-5-sonnet-20240620",
            messages = messages
        )

        // Send request to Anthropic API
        val response: AnthropicResponse = client.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(requestBody)
        }.body()

        // Extract and process the assistant's response
        val assistantMessage = response.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("")

        // Add assistant response to history
        messages.add(AnthropicMessage("assistant", assistantMessage))

        return assistantMessage
    }
}

fun main() {
    // Get API key from environment variable
    val apiKey = System.getenv("ANTHROPIC_KEY") ?: run {
        println("Error: ANTHROPIC_KEY environment variable not found. Please set it before running.")
        return
    }

    println("Welcome to the Anthropic Claude Chat!")
    println("Type your message and press Enter. Type 'exit' to quit.")
    println("----------------------------------------------------")

    val anthropicClient = AnthropicClient(apiKey)

    // Run the chat loop
    while (true) {
        print("\nYou: ")
        val userInput = readlnOrNull() ?: ""

        if (userInput.trim().equals("exit", ignoreCase = true)) {
            println("Goodbye!")
            break
        }

        try {
            runBlocking {
                val response = anthropicClient.sendMessage(userInput)
                println("\nClaude: $response")
            }
        } catch (e: Exception) {
            println("\nError: ${e.message}")
            e.printStackTrace()
        }
    }
}
