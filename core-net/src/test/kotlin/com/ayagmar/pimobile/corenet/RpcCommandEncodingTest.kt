package com.ayagmar.pimobile.corenet

import com.ayagmar.pimobile.corerpc.CycleModelCommand
import com.ayagmar.pimobile.corerpc.CycleThinkingLevelCommand
import com.ayagmar.pimobile.corerpc.NewSessionCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RpcCommandEncodingTest {
    @Test
    fun `encodes cycle model command`() {
        val encoded = encodeRpcCommand(Json, CycleModelCommand(id = "cycle-1"))

        assertEquals("cycle_model", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("cycle-1", encoded["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes cycle thinking level command`() {
        val encoded = encodeRpcCommand(Json, CycleThinkingLevelCommand(id = "thinking-1"))

        assertEquals("cycle_thinking_level", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("thinking-1", encoded["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes new session command`() {
        val encoded = encodeRpcCommand(Json, NewSessionCommand(id = "new-1"))

        assertEquals("new_session", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("new-1", encoded["id"]?.jsonPrimitive?.content)
    }
}
