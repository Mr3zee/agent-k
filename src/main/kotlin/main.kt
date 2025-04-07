import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

// Anthropic API request and response models
@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    @EncodeDefault
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
)

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null
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
    private val logger = LoggerFactory.getLogger(AnthropicClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
        install(Logging) {
            level = LogLevel.ALL
        }
    }

    private val messages = mutableListOf<AnthropicMessage>()

    suspend fun sendMessage(message: String): String {
        logger.debug("Sending message to Claude: {}", message)

        // Add user message to history
        messages.add(AnthropicMessage("user", message))
        logger.trace("Added user message to history")

        // Create the request body
        val requestBody = AnthropicRequest(
            model = "claude-3-5-sonnet-20240620",
            messages = messages
        )
        logger.trace("Created request body with model: {}", requestBody.model)

        logger.debug("Sending request to Anthropic API")
        // Send request to Anthropic API
        val response: AnthropicResponse = client.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(requestBody)
        }.body()
        logger.debug("Received response from Anthropic API with id: {}", response.id)

        // Extract and process the assistant's response
        val assistantMessage = response.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("")
        logger.trace("Extracted assistant message, length: {}", assistantMessage.length)

        // Add assistant response to history
        messages.add(AnthropicMessage("assistant", assistantMessage))
        logger.trace("Added assistant response to history")

        logger.debug("Returning assistant message")
        return assistantMessage
    }
}

fun main() {
    val logger = LoggerFactory.getLogger("MainKt")
    logger.info("Starting Anthropic Claude Chat application")

    // Get API key from environment variable
    val apiKey = System.getenv("ANTHROPIC_KEY") ?: run {
        logger.error("ANTHROPIC_KEY environment variable not found. Please set it before running.")
        return
    }
    logger.debug("API key loaded successfully")

    logger.info("Welcome to the Anthropic Claude Chat!")
    logger.info("Type your message and press Enter. Type 'exit' to quit.")
    logger.info("----------------------------------------------------")

    // Also print to console for user interaction
    println("Welcome to the Anthropic Claude Chat!")
    println("Type your message and press Enter. Type 'exit' to quit.")
    println("----------------------------------------------------")

    val anthropicClient = AnthropicClient(apiKey)
    logger.debug("AnthropicClient initialized")

    // Run the chat loop
    while (true) {
        print("\nYou: ")
        val userInput = readlnOrNull() ?: ""
        logger.debug("User input received: {}", userInput)

        if (userInput.trim().equals("exit", ignoreCase = true)) {
            logger.info("User requested to exit the application")
            println("Goodbye!")
            break
        }

        try {
            logger.debug("Processing user input")
            runBlocking {
                val response = anthropicClient.sendMessage(userInput)
                logger.debug("Response received from Claude, length: {}", response.length)
                println("\nClaude: $response")
            }
        } catch (e: Exception) {
            logger.error("Error while processing message: {}", e.message, e)
            println("\nError: ${e.message}")
        }
    }

    logger.info("Application shutting down")
}
