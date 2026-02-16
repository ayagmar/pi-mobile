package com.ayagmar.pimobile.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCwdPreferenceStoreTest {
    @Test
    fun storesPreferredCwdPerHost() {
        val store = InMemorySessionCwdPreferenceStore()

        store.setPreferredCwd(hostId = "host-a", cwd = "/home/ayagmar/project-a")
        store.setPreferredCwd(hostId = "host-b", cwd = "/home/ayagmar/project-b")

        assertEquals("/home/ayagmar/project-a", store.getPreferredCwd("host-a"))
        assertEquals("/home/ayagmar/project-b", store.getPreferredCwd("host-b"))
    }

    @Test
    fun clearPreferredCwdRemovesOnlyTargetHost() {
        val store = InMemorySessionCwdPreferenceStore()

        store.setPreferredCwd(hostId = "host-a", cwd = "/home/ayagmar/project-a")
        store.setPreferredCwd(hostId = "host-b", cwd = "/home/ayagmar/project-b")

        store.clearPreferredCwd("host-a")

        assertNull(store.getPreferredCwd("host-a"))
        assertEquals("/home/ayagmar/project-b", store.getPreferredCwd("host-b"))
    }
}
