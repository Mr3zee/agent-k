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
    val content: List<ContentBlock>,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    @EncodeDefault
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    val tools: List<tools.AnthropicTool>? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed interface ContentBlock

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("text")
data class TextContentBlock(
    val text: String,
) : ContentBlock

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("tool_use")
data class ToolUseContentBlock(
    val id: String,
    val name: String,
    val input: Map<String, String>,
) : ContentBlock

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("tool_result")
data class ToolResultContentBlock(
    @SerialName("tool_use_id")
    val toolUseId: String,
    val content: String,
) : ContentBlock

@Serializable
data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val model: String,
    val content: List<ContentBlock>,
)

class AnthropicClient(private val apiKey: String) {
    private val logger = LoggerFactory.getLogger(AnthropicClient::class.java)

    private val contentBlockModule = SerializersModule {
        polymorphic(ContentBlock::class) {
            subclass(TextContentBlock::class)
            subclass(ToolUseContentBlock::class)
            subclass(ToolResultContentBlock::class)
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
        messages.add(AnthropicMessage("user", listOf(TextContentBlock(message))))
        logger.trace("Added user message to history")

        // Create the initial request body with tools
        val anthropicTools = if (availableTools.isNotEmpty()) {
            logger.debug("Including {} tools in request", availableTools.size)
            availableTools.map { tools.ToolSerializer.toAnthropicTool(it) }
        } else {
            null
        }

        // Process the response and handle any tool use requests
        val responseBuilder = StringBuilder()
        val initialResponse = sendRequestToClaude(anthropicTools)

        // Process the response content, which may include multiple tool use blocks
        processResponseContent(initialResponse, responseBuilder, anthropicTools)

        val assistantMessage = responseBuilder.toString()
        logger.trace("Extracted assistant message, length: {}", assistantMessage.length)

        logger.debug("Returning assistant message")
        return assistantMessage
    }

    /**
     * Sends a request to Claude with the current message history and tools.
     *
     * @param tools The tools to include in the request
     * @return The response from Claude
     */
    private suspend fun sendRequestToClaude(tools: List<tools.AnthropicTool>?): AnthropicResponse {
        val requestBody = AnthropicRequest(
            model = "claude-3-5-sonnet-20240620",
            messages = messages,
            tools = tools
        )
        logger.trace("Created request body with model: {}", requestBody.model)

        logger.debug("Sending request to Anthropic API")
        val response =  client.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(requestBody)
        }.body<AnthropicResponse>()

        // Add assistant response to history
        messages.add(AnthropicMessage("assistant", response.content))
        logger.trace("Added assistant response to history")

        return response
    }

    /**
     * Processes the response content, handling both text and tool use blocks.
     * This method can handle multiple tool use blocks in a single response.
     * It executes all tools first, then sends a single follow-up request.
     *
     * @param response The response from Claude
     * @param responseBuilder The StringBuilder to append the processed content to
     * @param tools The tools to include in follow-up requests
     */
    private suspend fun processResponseContent(
        response: AnthropicResponse,
        responseBuilder: StringBuilder,
        tools: List<tools.AnthropicTool>?,
    ) {
        logger.debug("Processing response with id: {}", response.id)

        // First, process all text blocks
        val toolUseBlocks = mutableListOf<ToolUseContentBlock>()

        for (block in response.content) {
            if (block is TextContentBlock) {
                responseBuilder.append(block.text)
            } else if (block is ToolUseContentBlock) {
                toolUseBlocks.add(block)
            }
        }

        // Then, process all tool use blocks if any
        if (toolUseBlocks.isNotEmpty()) {
            // Execute all tools and collect results
            val toolResults = executeAllTools(toolUseBlocks, responseBuilder)

            // If we have tool results, send a follow-up request
            if (toolResults.isNotEmpty()) {
                // Add all tool results to the conversation
                messages.add(AnthropicMessage(
                    role = "user",
                    toolResults.map { (toolUseBlock, result) ->
                        ToolResultContentBlock(
                            toolUseId = toolUseBlock.id,
                            content = result
                        )
                    }
                ))

                // Get a follow-up response from Claude with all tool results
                val followUpResponse = sendRequestToClaude(tools)
                logger.debug("Received follow-up response from Anthropic API with id: {}", followUpResponse.id)

                // Process the follow-up response, which may contain more tool use blocks
                val followUpBuilder = StringBuilder()
                processResponseContent(followUpResponse, followUpBuilder, tools)

                responseBuilder.append(followUpBuilder)
            }
        }
    }

    /**
     * Executes all tools in the given list of tool use blocks and collects their results.
     *
     * @param toolUseBlocks The list of tool use blocks to process
     * @param responseBuilder The StringBuilder to append the tool execution information to
     * @return A map of tool names to their execution results
     */
    private fun executeAllTools(
        toolUseBlocks: List<ToolUseContentBlock>,
        responseBuilder: StringBuilder,
    ): Map<ToolUseContentBlock, String> {
        val results = mutableMapOf<ToolUseContentBlock, String>()

        for (block in toolUseBlocks) {
            logger.debug("Tool use request received for tool: {}", block.name)
            responseBuilder.append("\n[Using tool: ${block.name}]\n")

            try {
                val tool = availableTools.find { it.name == block.name }
                    ?: throw IllegalArgumentException("Tool not found: ${block.name}")

                val result = tool.execute(block.input)
                logger.debug("Tool execution successful, result length: {}", result.length)

                responseBuilder.append("\n[Tool result: $result]\n")
                results[block] = result
            } catch (e: Exception) {
                logger.error("Error executing tool: {}", block.name, e)
                responseBuilder.append("\n[Error executing tool: ${e.message}]\n")
            }
        }

        return results
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
    logger.info("Claude can use tools to help answer your questions, including multiple tools in one request.")
    logger.info("----------------------------------------------------")

    // Also print to console for user interaction
    println("Welcome to the Anthropic Claude Chat!")
    println("Type your message and press Enter. Type 'exit' to quit.")
    println("Claude can use tools to help answer your questions, including multiple tools in one request.")
    println("----------------------------------------------------")

    val anthropicClient = AnthropicClient(apiKey)

    // Register the ReadFileTool
    val readFileTool = tools.ReadFileTool()
    anthropicClient.registerTool(readFileTool)
    logger.debug("Registered ReadFileTool")

    // Register the WriteFileTool
    val writeFileTool = tools.WriteFileTool()
    anthropicClient.registerTool(writeFileTool)
    logger.debug("Registered WriteFileTool")

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
