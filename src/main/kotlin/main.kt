import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val system: String = "",
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

class AnthropicClient(
    private val apiKey: String,
    private val safeMode: Boolean = false,
    private val requestTimeoutSeconds: Long = 120, // Default timeout: 2 minutes
) {
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
            level = LogLevel.NONE
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutSeconds * 1000
            connectTimeoutMillis = 30_000  // 30 seconds
            socketTimeoutMillis = 60_000   // 60 seconds
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
     * @return The response from Claude as a list of content blocks
     */
    suspend fun sendMessage(message: String) {
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

        // Send the request to Claude
        val initialResponse = sendRequestToClaude(anthropicTools)

        // Process the response content, which may include multiple tool use blocks
        processResponseContent(initialResponse, anthropicTools)
    }

    /**
     * Sends a request to Claude with the current message history and tools.
     *
     * @param tools The tools to include in the request
     * @return The response from Claude
     */
    private suspend fun sendRequestToClaude(tools: List<tools.AnthropicTool>?): AnthropicResponse {
        // Update system information before each request
        val systemInfo = generateSystemInfo()

        val requestBody = AnthropicRequest(
            model = "claude-3-7-sonnet-20250219",
            messages = messages,
            tools = tools,
            system = systemInfo,
            maxTokens = 16000,
        )
        logger.trace("Created request body with model: {}", requestBody.model)

        logger.debug("Sending request to Anthropic API")
        val response = client.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(requestBody)
        }

        val body = try {
            response.body<AnthropicResponse>()
        } catch (e: Exception) {
            logger.error("Error while parsing response body: {}", e.message, e)
            val text = response.bodyAsText()
            println("Error while parsing response (${response.status}) body: $text")
            throw e
        }

        // Add assistant response to history
        messages.add(AnthropicMessage("assistant", body.content))
        logger.trace("Added assistant response to history")

        return body
    }

    /**
     * Processes the response content, handling both text and tool use blocks.
     * This method can handle multiple tool use blocks in a single response.
     * It executes all tools first, then sends a single follow-up request.
     *
     * @param response The response from Claude
     * @param tools The tools to include in follow-up requests
     * @return A list of content blocks representing the processed response
     */
    private suspend fun processResponseContent(
        response: AnthropicResponse,
        tools: List<tools.AnthropicTool>?,
    ) {
        logger.debug("Processing response with id: {}", response.id)

        val results = mutableMapOf<ToolUseContentBlock, String>()

        for (block in response.content) {
            when (block) {
                is TextContentBlock -> {
                    println("Agent-K: ${block.text}")
                }

                is ToolUseContentBlock -> {
                    // Execute all tools and collect results
                    val toolResult = executeTool(block)

                    // Add tool content blocks to the result
                    results[block] = toolResult
                }

                is ToolResultContentBlock -> {}
            }
        }

        if (results.isEmpty()) {
            return
        }

        // Add all tool results to the conversation
        messages.add(AnthropicMessage(
            role = "user",
            results.map { (toolUseBlock, result) ->
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
        processResponseContent(followUpResponse, tools)
    }

    /**
     * Executes all tools in the given list of tool use blocks and collects their results.
     *
     * @return A pair of:
     *         - A map of tool use blocks to their execution results
     *         - A list of content blocks representing tool execution information
     */
    private suspend fun executeTool(block: ToolUseContentBlock): String {
        logger.debug("Tool use request received for tool: {}", block.name)

        return try {
            val tool = availableTools.find { it.name == block.name }
                ?: throw IllegalArgumentException("Tool not found: ${block.name}")

            // Always display tool information in the console
            println("\n[Using tool: ${tool.name}]")
            println("[Description: ${tool.description}]")
            println("[Parameters:")
            block.input.forEach { (key, value) ->
                val paramDescription = tool.inputSchema.properties[key]?.description ?: "No description available"
                val displayValue = (if (value.length > 100) "${value.take(97)}..." else value)
                    .replace("\n", "\\n")
                println("  - $key: $displayValue")
                println("    Description: $paramDescription")
            }
            println("]")

            // In safe mode, ask for user permission before executing the tool
            if (safeMode) {
                val permissionGranted = askForPermission()
                if (!permissionGranted) {
                    logger.info("User denied permission to execute tool: {}", block.name)
                    return "Tool execution denied by user"
                }
            }

            val result = tool.execute(block.input)
            logger.debug("Tool execution successful, result length: {}", result.length)

            val strippedResult = result
                .replace("\n", "\\n")
                .let { if (it.length > 100) "${it.take(97)}..." else it }

            println("[Tool result: $strippedResult]")

            "Tool result: $result"
        } catch (e: Exception) {
            logger.error("Error executing tool: {}", block.name, e)
            println("Error executing tool ${block.name}: ${e.message}]")
            "Error executing tool: ${e.message}"
        }
    }

    /**
     * Asks the user for permission to execute a tool.
     *
     * @return True if the user grants permission, false otherwise
     */
    private fun askForPermission(): Boolean {
        println("\n===== SAFE MODE: TOOL EXECUTION REQUEST =====")
        println("\nDo you want to allow this tool execution? (y/n): ")
        val response = readlnOrNull()?.trim()?.lowercase() ?: "n"
        return response == "y" || response == "yes"
    }


}

/**
 * Loads the system prompt from the resource file.
 *
 * @return The system prompt from the resource file, or a default prompt if the file cannot be read
 */
fun loadSystemPrompt(): String {
    return try {
        val inputStream = object {}.javaClass.getResourceAsStream("/system_prompt.txt")
        inputStream?.bufferedReader()?.use { it.readText() } ?: defaultSystemPrompt()
    } catch (e: Exception) {
        LoggerFactory.getLogger("MainKt").error("Error loading system prompt: {}", e.message, e)
        defaultSystemPrompt()
    }
}

/**
 * Returns a default system prompt if the resource file cannot be read.
 *
 * @return A default system prompt
 */
fun defaultSystemPrompt(): String {
    return "You are an AI agent called Agent-K. You are a general purpose AI agent capable of changing its own code using available tools. You are using Claude AI LLM reasoning capabilities."
}

/**
 * Generates system information including current date, time, time zone, and OS details.
 * Combines the dynamic environment information with the system prompt from the resource file.
 * Ensures all environment variables are not null.
 *
 * @return A formatted string with system information and the system prompt
 */
fun generateSystemInfo(): String {
    val currentDateTime = LocalDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val formattedDateTime = currentDateTime.format(formatter)
    val timeZone = ZoneId.systemDefault()
    val osName = System.getProperty("os.name") ?: "Unknown OS"
    val osVersion = System.getProperty("os.version") ?: "Unknown Version"
    val osArch = System.getProperty("os.arch") ?: "Unknown Architecture"

    val environmentInfo = """
        Current Environment:
        Date and Time: $formattedDateTime
        Time Zone: $timeZone
        Operating System: $osName $osVersion ($osArch)
    """.trimIndent()

    // Load the system prompt from the resource file
    val systemPrompt = loadSystemPrompt()

    // Combine the environment information with the system prompt
    return "$environmentInfo\n\n$systemPrompt"
}

fun main(args: Array<String>) = runBlocking {
    val logger = LoggerFactory.getLogger("MainKt")
    logger.info("Starting Anthropic Claude Chat application")

    // Parse command-line arguments
    val safeMode = args.contains("--safe-mode")
    if (safeMode) {
        logger.info("Safe mode enabled: Tool executions will require user permission")
    }

    // Parse timeout argument
    val timeoutSeconds = args.withIndex()
        .firstOrNull { (_, value) -> value == "--timeout" }
        ?.let { (index, _) ->
            if (index + 1 < args.size) {
                args[index + 1].toLongOrNull()
            } else null
        } ?: 120L // Default: 2 minutes

    logger.info("Request timeout set to $timeoutSeconds seconds")

    // Get API key from environment variable
    val apiKey = System.getenv("ANTHROPIC_KEY") ?: run {
        logger.error("ANTHROPIC_KEY environment variable not found. Please set it before running.")
        return@runBlocking
    }
    logger.debug("API key loaded successfully")

    logger.info("Welcome to the Anthropic Claude Chat!")
    logger.info("Type your message and press Enter. Type 'exit' to quit.")
    logger.info("Claude can use tools to help answer your questions, including multiple tools in one request.")
    if (safeMode) {
        logger.info("Safe mode is enabled: You will be asked for permission before any tool is executed")
    }
    logger.info("----------------------------------------------------")

    // Also print to console for user interaction
    println("Welcome to the Anthropic Claude Chat!")
    println("Type your message and press Enter. Type 'exit' to quit.")
    println("Claude can use tools to help answer your questions, including multiple tools in one request.")
    if (safeMode) {
        println("Safe mode is enabled: You will be asked for permission before any tool is executed")
    }
    println("Request timeout set to $timeoutSeconds seconds")
    println("----------------------------------------------------")

    val anthropicClient = AnthropicClient(apiKey, safeMode, timeoutSeconds)

    // Register the ReadFileTool
    val readFileTool = tools.ReadFileTool()
    anthropicClient.registerTool(readFileTool)
    logger.debug("Registered ReadFileTool")

    // Register the WriteFileTool
    val writeFileTool = tools.WriteFileTool()
    anthropicClient.registerTool(writeFileTool)
    logger.debug("Registered WriteFileTool")

    // Register the TerminalTool
    val terminalTool = tools.TerminalTool()
    anthropicClient.registerTool(terminalTool)
    logger.debug("Registered TerminalTool")

    // Register the NetworkRequestsTool
    val networkRequestsTool = tools.NetworkRequestsTool()
    anthropicClient.registerTool(networkRequestsTool)
    logger.debug("Registered NetworkRequestsTool")

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
                    anthropicClient.sendMessage(userInput)
                    println()
                } catch (e: Exception) {
                    logger.error("Error while processing message: {}", e.message, e)
                    println("\nError: ${e.message}")
                }
            }
        }
    }

    logger.info("Application shutting down")
}
