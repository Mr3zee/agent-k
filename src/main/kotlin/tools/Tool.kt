package tools

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Interface for tools that can be used by Claude.
 * Tools must adhere to Anthropic API specifications.
 */
interface Tool {
    /**
     * The name of the tool. Should be a unique identifier.
     */
    val name: String

    /**
     * Human-readable description of what the tool does.
     * This helps Claude understand when and how to use the tool.
     */
    val description: String

    /**
     * The input schema for the tool, defining what parameters it accepts.
     */
    val inputSchema: ToolInputSchema

    /**
     * Execute the tool with the given parameters.
     * 
     * @param parameters The parameters to use when executing the tool
     * @return The result of executing the tool
     */
    fun execute(parameters: Map<String, String>): String
}

/**
 * Represents the schema for tool inputs according to Anthropic API specifications.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ToolInputSchema(
    /**
     * The type of the schema, always "object" for Anthropic tools.
     */
    @EncodeDefault
    val type: String = "object",

    /**
     * Properties that the tool accepts.
     */
    val properties: Map<String, ToolProperty>,

    /**
     * List of required property names.
     */
    val required: List<String>
)

/**
 * Represents a property in a tool's input schema.
 */
@Serializable
data class ToolProperty(
    /**
     * The type of the property (e.g., "string", "integer", "boolean").
     */
    val type: String,

    /**
     * Human-readable description of the property.
     */
    val description: String
)
