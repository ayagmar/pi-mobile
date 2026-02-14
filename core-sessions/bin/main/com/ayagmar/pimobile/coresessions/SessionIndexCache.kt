package com.ayagmar.pimobile.coresessions

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

interface SessionIndexCache {
    suspend fun read(hostId: String): CachedSessionIndex?

    suspend fun write(index: CachedSessionIndex)
}

class InMemorySessionIndexCache : SessionIndexCache {
    private val cacheByHost = linkedMapOf<String, CachedSessionIndex>()

    override suspend fun read(hostId: String): CachedSessionIndex? = cacheByHost[hostId]

    override suspend fun write(index: CachedSessionIndex) {
        cacheByHost[index.hostId] = index
    }
}

class FileSessionIndexCache(
    private val cacheDirectory: Path,
    private val json: Json = defaultJson,
) : SessionIndexCache {
    override suspend fun read(hostId: String): CachedSessionIndex? {
        val filePath = cacheFilePath(hostId)

        val raw =
            if (filePath.exists()) {
                runCatching { filePath.readText() }.getOrNull()
            } else {
                null
            }

        val decoded =
            raw?.let { serialized ->
                runCatching {
                    json.decodeFromString(CachedSessionIndex.serializer(), serialized)
                }.getOrNull()
            }

        return decoded
    }

    override suspend fun write(index: CachedSessionIndex) {
        Files.createDirectories(cacheDirectory)

        val filePath = cacheFilePath(index.hostId)
        val raw = json.encodeToString(CachedSessionIndex.serializer(), index)
        filePath.writeText(raw)
    }

    private fun cacheFilePath(hostId: String): Path = cacheDirectory.resolve("${sanitizeHostId(hostId)}.json")

    private fun sanitizeHostId(hostId: String): String {
        return hostId.map { character ->
            if (character.isLetterOrDigit() || character == '_' || character == '-') {
                character
            } else {
                '_'
            }
        }.joinToString("")
    }

    companion object {
        val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = false
            }
    }
}
