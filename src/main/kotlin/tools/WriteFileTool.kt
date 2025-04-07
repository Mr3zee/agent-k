package tools

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * A tool that allows writing content to files in the file system.
 */
class WriteFileTool : Tool {
    private val logger = LoggerFactory.getLogger(WriteFileTool::class.java)
    
    override val name: String = "write_file"
    
    override val description: String = "Write content to a file in the file system. " +
            "Use this tool when you need to create or update a file with specific content. " +
            "The file path should be absolute or relative to the current working directory."
    
    override val inputSchema: ToolInputSchema = ToolInputSchema(
        properties = mapOf(
            "file_path" to ToolProperty(
                type = "string",
                description = "The path to the file to write. Can be absolute or relative to the current working directory."
            ),
            "content" to ToolProperty(
                type = "string",
                description = "The content to write to the file."
            )
        ),
        required = listOf("file_path", "content")
    )
    
    override fun execute(parameters: Map<String, String>): String {
        val filePath = parameters["file_path"] ?: throw IllegalArgumentException("file_path parameter is required")
        val content = parameters["content"] ?: throw IllegalArgumentException("content parameter is required")
        
        logger.debug("Writing to file: {}", filePath)
        
        return try {
            val path = Paths.get(filePath)
            val directory = path.parent
            
            // Create parent directories if they don't exist
            if (directory != null && !Files.exists(directory)) {
                Files.createDirectories(directory)
                logger.debug("Created directory: {}", directory)
            }
            
            // Write the content to the file
            File(filePath).writeText(content)
            logger.debug("Successfully wrote to file: {}, content length: {}", filePath, content.length)
            
            "Successfully wrote ${content.length} characters to $filePath"
        } catch (e: Exception) {
            logger.error("Error writing to file: {}", filePath, e)
            "Error writing to file: ${e.message}"
        }
    }
}