package com.ayagmar.pimobile.corenet

import com.ayagmar.pimobile.corerpc.CycleModelCommand
import com.ayagmar.pimobile.corerpc.CycleThinkingLevelCommand
import com.ayagmar.pimobile.corerpc.NewSessionCommand
import com.ayagmar.pimobile.corerpc.SetFollowUpModeCommand
import com.ayagmar.pimobile.corerpc.SetSteeringModeCommand
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

    @Test
    fun `encodes set steering mode command`() {
        val encoded = encodeRpcCommand(Json, SetSteeringModeCommand(id = "steer-mode-1", mode = "all"))

        assertEquals("set_steering_mode", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("steer-mode-1", encoded["id"]?.jsonPrimitive?.content)
        assertEquals("all", encoded["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes set follow up mode command`() {
        val encoded = encodeRpcCommand(Json, SetFollowUpModeCommand(id = "follow-up-mode-1", mode = "one-at-a-time"))

        assertEquals("set_follow_up_mode", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("follow-up-mode-1", encoded["id"]?.jsonPrimitive?.content)
        assertEquals("one-at-a-time", encoded["mode"]?.jsonPrimitive?.content)
    }
}
