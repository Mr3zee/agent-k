package tools

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * A tool that allows executing commands in the terminal.
 * This tool is OS-agnostic and works on Windows, macOS, and Linux.
 */
class TerminalTool : Tool {
    private val logger = LoggerFactory.getLogger(TerminalTool::class.java)
    
    override val name: String = "terminal"
    
    override val description: String = "Execute a command in the terminal. " +
            "Use this tool when you need to run shell commands or scripts. " +
            "The command will be executed in the current working directory. " +
            "This tool works on all operating systems (Windows, macOS, Linux)."
    
    override val inputSchema: ToolInputSchema = ToolInputSchema(
        properties = mapOf(
            "command" to ToolProperty(
                type = "string",
                description = "The command to execute in the terminal. On Windows, this will be executed with cmd.exe, on macOS/Linux with /bin/sh."
            ),
            "timeout" to ToolProperty(
                type = "string",
                description = "Optional timeout in seconds. If the command doesn't complete within this time, it will be terminated. Default is 30 seconds."
            )
        ),
        required = listOf("command")
    )
    
    override fun execute(parameters: Map<String, String>): String {
        val command = parameters["command"] ?: throw IllegalArgumentException("command parameter is required")
        val timeoutStr = parameters["timeout"] ?: "30"
        val timeout = try {
            timeoutStr.toLong()
        } catch (e: NumberFormatException) {
            logger.warn("Invalid timeout value: {}, using default of 30 seconds", timeoutStr)
            30L
        }
        
        logger.debug("Executing command: {}", command)
        
        return try {
            // Create a process builder with the appropriate shell based on the OS
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val processBuilder = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("/bin/sh", "-c", command)
            }
            
            // Redirect error stream to output stream
            processBuilder.redirectErrorStream(true)
            
            // Start the process
            val process = processBuilder.start()
            
            // Wait for the process to complete with timeout
            val completed = process.waitFor(timeout, TimeUnit.SECONDS)
            
            if (!completed) {
                // Process didn't complete within the timeout, destroy it
                process.destroy()
                logger.warn("Command execution timed out after {} seconds: {}", timeout, command)
                return "Error: Command execution timed out after $timeout seconds"
            }
            
            // Read the output
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLines().joinToString("\n")
            
            // Check the exit code
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                logger.warn("Command execution failed with exit code {}: {}", exitCode, command)
                "Command execution failed with exit code $exitCode:\n$output"
            } else {
                logger.debug("Command execution successful, output length: {}", output.length)
                output
            }
        } catch (e: Exception) {
            logger.error("Error executing command: {}", command, e)
            "Error executing command: ${e.message}"
        }
    }
}