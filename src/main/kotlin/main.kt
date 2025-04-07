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
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
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
    val tools: List<tools.AnthropicTool>? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed interface ContentBlock

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("text")
data class TextContentBlock(
    val text: String
) : ContentBlock

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("tool_use")
data class ToolUseContentBlock(
    val id: String,
    val name: String,
    val input: Map<String, String>
) : ContentBlock

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

    private val contentBlockModule = SerializersModule {
        polymorphic(ContentBlock::class) {
            subclass(TextContentBlock::class)
            subclass(ToolUseContentBlock::class)
        }
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                serializersModule = contentBlockModule
                classDiscriminator = "type"
            })
        }
        install(Logging) {
            level = LogLevel.ALL
        }
    }

    private val messages = mutableListOf<AnthropicMessage>()
    private val availableTools = mutableListOf<tools.Tool>()

    /**
     * Registers a tool that can be used by Claude.
     * 
     * @param tool The tool to register
     */
    fun registerTool(tool: tools.Tool) {
        logger.debug("Registering tool: {}", tool.name)
        availableTools.add(tool)
    }

    /**
     * Sends a message to Claude and returns the response.
     * 
     * @param message The message to send
     * @return The response from Claude
     */
    suspend fun sendMessage(message: String): String {
        logger.debug("Sending message to Claude: {}", message)

        // Add user message to history
        messages.add(AnthropicMessage("user", message))
        logger.trace("Added user message to history")

        // Create the request body with tools
        val requestBody = if (availableTools.isNotEmpty()) {
            logger.debug("Including {} tools in request", availableTools.size)
            val anthropicTools = availableTools.map { tools.ToolSerializer.toAnthropicTool(it) }
            AnthropicRequest(
                model = "claude-3-5-sonnet-20240620",
                messages = messages,
                tools = anthropicTools
            )
        } else {
            AnthropicRequest(
                model = "claude-3-5-sonnet-20240620",
                messages = messages
            )
        }
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

        // Process the response content
        val responseBuilder = StringBuilder()

        for (block in response.content) {
            when (block) {
                is TextContentBlock -> {
                    responseBuilder.append(block.text)
                }
                is ToolUseContentBlock -> {
                    logger.debug("Tool use request received for tool: {}", block.name)
                    responseBuilder.append("\n[Using tool: ${block.name}]\n")

                    try {
                        val tool = availableTools.find { it.name == block.name }
                            ?: throw IllegalArgumentException("Tool not found: ${block.name}")

                        val result = tool.execute(block.input)
                        logger.debug("Tool execution successful, result length: {}", result.length)

                        // Add tool result to the conversation
                        messages.add(AnthropicMessage(
                            role = "user", 
                            content = "Tool ${block.name} returned: $result"
                        ))

                        // Get a follow-up response from Claude with the tool result
                        val followUpResponse = client.post("https://api.anthropic.com/v1/messages") {
                            contentType(ContentType.Application.Json)
                            header("x-api-key", apiKey)
                            header("anthropic-version", "2023-06-01")
                            setBody(requestBody)
                        }.body<AnthropicResponse>()

                        // Add the follow-up response to the result
                        val followUpText = followUpResponse.content
                            .filterIsInstance<TextContentBlock>()
                            .joinToString("") { it.text }

                        responseBuilder.append("\n[Tool result: $result]\n")
                        responseBuilder.append(followUpText)
                    } catch (e: Exception) {
                        logger.error("Error executing tool: {}", block.name, e)
                        responseBuilder.append("\n[Error executing tool: ${e.message}]\n")
                    }
                }
            }
        }

        val assistantMessage = responseBuilder.toString()
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
    logger.info("Claude can use tools to help answer your questions.")
    logger.info("----------------------------------------------------")

    // Also print to console for user interaction
    println("Welcome to the Anthropic Claude Chat!")
    println("Type your message and press Enter. Type 'exit' to quit.")
    println("Claude can use tools to help answer your questions.")
    println("----------------------------------------------------")

    val anthropicClient = AnthropicClient(apiKey)

    // Register the ReadFileTool
    val readFileTool = tools.ReadFileTool()
    anthropicClient.registerTool(readFileTool)
    logger.debug("Registered ReadFileTool")

    logger.debug("AnthropicClient initialized")

    // Run the chat loop
    while (true) {
        print("\nYou: ")
        val userInput = readlnOrNull() ?: ""
        logger.debug("User input received: {}", userInput)

        when {
            userInput.trim().equals("exit", ignoreCase = true) -> {
                logger.info("User requested to exit the application")
                println("Goodbye!")
                break
            }
            else -> {
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
        }
    }

    logger.info("Application shutting down")
}
