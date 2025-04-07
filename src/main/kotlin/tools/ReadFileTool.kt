package tools

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * A tool that allows reading files from the file system.
 */
class ReadFileTool : Tool {
    private val logger = LoggerFactory.getLogger(ReadFileTool::class.java)
    
    override val name: String = "read_file"
    
    override val description: String = "Read the contents of a file from the file system. " +
            "Use this tool when you need to access the content of a specific file. " +
            "The file path should be absolute or relative to the current working directory."
    
    override val inputSchema: ToolInputSchema = ToolInputSchema(
        properties = mapOf(
            "file_path" to ToolProperty(
                type = "string",
                description = "The path to the file to read. Can be absolute or relative to the current working directory."
            )
        ),
        required = listOf("file_path")
    )
    
    override fun execute(parameters: Map<String, String>): String {
        val filePath = parameters["file_path"] ?: throw IllegalArgumentException("file_path parameter is required")
        logger.debug("Reading file: {}", filePath)
        
        return try {
            val path = Paths.get(filePath)
            if (!Files.exists(path)) {
                logger.error("File not found: {}", filePath)
                "Error: File not found: $filePath"
            } else if (!Files.isRegularFile(path)) {
                logger.error("Not a regular file: {}", filePath)
                "Error: Not a regular file: $filePath"
            } else {
                val content = File(filePath).readText()
                logger.debug("Successfully read file: {}, content length: {}", filePath, content.length)
                content
            }
        } catch (e: Exception) {
            logger.error("Error reading file: {}", filePath, e)
            "Error reading file: ${e.message}"
        }
    }
}