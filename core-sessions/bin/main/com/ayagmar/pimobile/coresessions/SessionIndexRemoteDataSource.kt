package com.ayagmar.pimobile.coresessions

interface SessionIndexRemoteDataSource {
    suspend fun fetch(hostId: String): List<SessionGroup>
}
