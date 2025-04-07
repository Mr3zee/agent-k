package tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializes tools to the format expected by the Anthropic API.
 */
object ToolSerializer {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Converts a Tool to an AnthropicTool that can be serialized for the Anthropic API.
     * 
     * @param tool The tool to convert
     * @return An AnthropicTool representation
     */
    fun toAnthropicTool(tool: Tool): AnthropicTool {
        return AnthropicTool(
            name = tool.name,
            description = tool.description,
            input_schema = tool.inputSchema
        )
    }

}

/**
 * Represents a tool in the format expected by the Anthropic API.
 */
@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    val input_schema: ToolInputSchema
)

/**
 * Represents a list of tools in the format expected by the Anthropic API.
 */
@Serializable
data class AnthropicTools(
    val tools: List<AnthropicTool>
)
