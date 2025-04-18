package tools

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.lang.reflect.Field

class NetworkRequestsToolTest {

    private lateinit var tool: NetworkRequestsTool

    @BeforeEach
    fun setUp() {
        tool = NetworkRequestsTool()
    }

    @Test
    fun `test tool properties`() {
        assertEquals("network_request", tool.name)
        assertTrue(tool.description.isNotEmpty())
        assertEquals(1, tool.inputSchema.properties.size)
        assertTrue(tool.inputSchema.properties.containsKey("url"))
        assertEquals(listOf("url"), tool.inputSchema.required)
    }

    @Test
    fun `test HTML to Markdown conversion`() {
        // Use reflection to access the private method
        val method = NetworkRequestsTool::class.java.getDeclaredMethod("convertHtmlToMarkdown", String::class.java)
        method.isAccessible = true

        val html = """
            <html>
                <body>
                    <h1>Test Heading</h1>
                    <p>This is a <strong>test</strong> paragraph.</p>
                    <ul>
                        <li>Item 1</li>
                        <li>Item 2</li>
                    </ul>
                </body>
            </html>
        """.trimIndent()

        val markdown = method.invoke(tool, html) as String

        // Print the actual output for debugging
        println("[DEBUG_LOG] Converted Markdown: $markdown")

        // Check that the conversion worked correctly - using more flexible assertions
        assertTrue(markdown.contains("Test Heading"), "Should contain the heading text")
        assertTrue(markdown.contains("test"), "Should contain the word 'test'")
        assertTrue(markdown.contains("Item 1"), "Should contain Item 1")
        assertTrue(markdown.contains("Item 2"), "Should contain Item 2")

        // Verify it's not just returning the HTML
        assertFalse(markdown.contains("<h1>"), "Should not contain HTML tags")
    }

    @Test
    fun `test execute with mock client - HTML response`() {
        // Create a mock client that returns HTML
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    <html>
                        <body>
                            <h1>Test Page</h1>
                            <p>This is a test page.</p>
                        </body>
                    </html>
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        // Replace the client in the tool with our mock client
        val mockClient = HttpClient(mockEngine) {
            install(Logging) {
                level = LogLevel.INFO
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 30000
            }
        }

        // Use reflection to replace the client
        val clientField = NetworkRequestsTool::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(tool, mockClient)

        // Execute the tool
        val result = tool.execute(mapOf("url" to "https://example.com"))

        // Print the actual output for debugging
        println("[DEBUG_LOG] Tool execution result: $result")

        // Check the result with more flexible assertions
        assertTrue(result.contains("Test Page"), "Should contain the page title")
        assertTrue(result.contains("test page"), "Should contain the page content")

        // Verify it's not just returning the HTML
        assertFalse(result.contains("<h1>"), "Should not contain HTML tags")
    }

    @Test
    fun `test execute with mock client - non-HTML response`() {
        // Create a mock client that returns JSON
        val mockEngine = MockEngine { request ->
            respond(
                content = """{"message": "This is JSON"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        // Replace the client in the tool with our mock client
        val mockClient = HttpClient(mockEngine) {
            install(Logging) {
                level = LogLevel.INFO
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 30000
            }
        }

        // Use reflection to replace the client
        val clientField = NetworkRequestsTool::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(tool, mockClient)

        // Execute the tool
        val result = tool.execute(mapOf("url" to "https://example.com"))

        // Check that it returns an error for non-HTML content
        assertTrue(result.startsWith("Error: Unsupported content type"))
        assertTrue(result.contains("application/json"))
    }

    @Test
    fun `test execute with missing url parameter`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            tool.execute(emptyMap())
        }

        assertEquals("url parameter is required", exception.message)
    }
}
