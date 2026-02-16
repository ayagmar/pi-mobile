package com.ayagmar.pimobile.sessions

internal fun formatCwdTail(
    cwd: String,
    maxSegments: Int = 2,
): String {
    if (cwd.isBlank()) return "(unknown)"

    val segments = cwd.trim().trimEnd('/').split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) {
        return "/"
    }

    return segments.takeLast(maxSegments).joinToString("/")
}
