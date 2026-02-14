package com.ayagmar.pimobile.corerpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RpcMessageParserTest {
    private val parser = RpcMessageParser()

    @Test
    fun `parse response success`() {
        val line =
            """
            {
              "id":"req-1",
              "type":"response",
              "command":"get_state",
              "success":true,
              "data":{"sessionId":"abc"}
            }
            """.trimIndent()

        val parsed = parser.parse(line)

        val response = assertIs<RpcResponse>(parsed)
        assertEquals("req-1", response.id)
        assertEquals("get_state", response.command)
        assertTrue(response.success)
        val responseData = assertNotNull(response.data)
        assertEquals("abc", responseData["sessionId"]?.toString()?.trim('"'))
        assertNull(response.error)
    }

    @Test
    fun `parse response failure`() {
        val line =
            """
            {
              "id":"req-2",
              "type":"response",
              "command":"set_model",
              "success":false,
              "error":"Model not found"
            }
            """.trimIndent()

        val parsed = parser.parse(line)

        val response = assertIs<RpcResponse>(parsed)
        assertEquals("req-2", response.id)
        assertEquals("set_model", response.command)
        assertFalse(response.success)
        assertEquals("Model not found", response.error)
    }

    @Test
    fun `parse message update event`() {
        val line =
            """
            {
              "type":"message_update",
              "message":{"role":"assistant"},
              "assistantMessageEvent":{
                "type":"text_delta",
                "contentIndex":0,
                "delta":"Hello"
              }
            }
            """.trimIndent()

        val parsed = parser.parse(line)

        val event = assertIs<MessageUpdateEvent>(parsed)
        assertEquals("message_update", event.type)
        val deltaEvent = assertNotNull(event.assistantMessageEvent)
        assertEquals("text_delta", deltaEvent.type)
        assertEquals(0, deltaEvent.contentIndex)
        assertEquals("Hello", deltaEvent.delta)
    }

    @Test
    fun `parse tool execution events`() {
        val startLine =
            """
            {
              "type":"tool_execution_start",
              "toolCallId":"call-1",
              "toolName":"bash",
              "args":{"command":"pwd"}
            }
            """.trimIndent()
        val updateLine =
            """
            {
              "type":"tool_execution_update",
              "toolCallId":"call-1",
              "toolName":"bash",
              "partialResult":{"content":[{"type":"text","text":"out"}]}
            }
            """.trimIndent()
        val endLine =
            """
            {
              "type":"tool_execution_end",
              "toolCallId":"call-1",
              "toolName":"bash",
              "result":{"content":[]},
              "isError":false
            }
            """.trimIndent()

        val start = assertIs<ToolExecutionStartEvent>(parser.parse(startLine))
        assertEquals("bash", start.toolName)

        val update = assertIs<ToolExecutionUpdateEvent>(parser.parse(updateLine))
        assertEquals("call-1", update.toolCallId)
        assertNotNull(update.partialResult)

        val end = assertIs<ToolExecutionEndEvent>(parser.parse(endLine))
        assertEquals("call-1", end.toolCallId)
        assertFalse(end.isError)
    }

    @Test
    fun `parse extension ui request event`() {
        val line =
            """
            {
              "type":"extension_ui_request",
              "id":"uuid-1",
              "method":"confirm",
              "title":"Clear session?",
              "message":"All messages will be lost.",
              "timeout":5000
            }
            """.trimIndent()

        val parsed = parser.parse(line)

        val event = assertIs<ExtensionUiRequestEvent>(parsed)
        assertEquals("uuid-1", event.id)
        assertEquals("confirm", event.method)
        assertEquals("Clear session?", event.title)
        assertEquals(5000, event.timeout)
    }
}
