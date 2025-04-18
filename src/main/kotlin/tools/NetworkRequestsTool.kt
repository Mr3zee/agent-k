package tools

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory

/**
 * A tool that makes network requests to a given URL and converts HTML responses to Markdown.
 */
class NetworkRequestsTool : Tool {
    private val logger = LoggerFactory.getLogger(NetworkRequestsTool::class.java)

    override val name: String = "network_request"

    override val description: String = "Makes a network request to a given URL and returns the content. " +
            "If the content is HTML, it will be converted to Markdown format. " +
            "This tool is useful for fetching web content in a readable format. " +
            "Only HTML content is supported - other content types will result in an error."

    override val inputSchema: ToolInputSchema = ToolInputSchema(
        properties = mapOf(
            "url" to ToolProperty(
                type = "string",
                description = "The URL to make the request to. Must be a valid HTTP or HTTPS URL."
            )
        ),
        required = listOf("url")
    )

    private val client = HttpClient(CIO) {
        install(Logging) {
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000 // 30 seconds
            connectTimeoutMillis = 15000 // 15 seconds
            socketTimeoutMillis = 30000  // 30 seconds
        }
    }

    override suspend fun execute(parameters: Map<String, String>): String {
        val url = parameters["url"] ?: throw IllegalArgumentException("url parameter is required")
        logger.debug("Making network request to: {}", url)

        return try {
            val response = client.get(url)

            // Check if content type is HTML
            val contentType = response.headers[HttpHeaders.ContentType]
            if (contentType == null || !contentType.contains("text/html", ignoreCase = true)) {
                logger.error("Unsupported content type: {}", contentType)
                return "Error: Unsupported content type: $contentType. This tool can only process HTML content."
            }

            val htmlContent = response.bodyAsText()

            // Convert HTML to Markdown
            val markdownContent = convertHtmlToMarkdown(htmlContent)
            logger.debug("Successfully converted HTML to Markdown, content length: {}", markdownContent.length)

            markdownContent
        } catch (e: Exception) {
            logger.error("Error making network request: {}", e.message, e)
            "Error making network request: ${e.message}"
        }
    }

    /**
     * Converts HTML content to Markdown format.
     * 
     * @param html The HTML content to convert
     * @return The converted Markdown content
     */
    private fun convertHtmlToMarkdown(html: String): String {
        val converter = FlexmarkHtmlConverter.builder().build()
        return converter.convert(html)
    }
}
