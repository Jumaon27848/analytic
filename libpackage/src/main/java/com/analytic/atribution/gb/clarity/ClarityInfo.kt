package com.analytic.atribution.gb.clarity

import org.json.JSONObject

/**
 * Microsoft Clarity configuration delivered by the analytics admin console.
 *
 * The library uses [projectId] (a.k.a. Clarity API key) and [isLoggingEnabled] to drive
 * Clarity SDK initialization. If [projectId] is null/empty the library skips Clarity
 * entirely.
 */
data class ClarityInfo(
    val projectId: String?,
    val isLoggingEnabled: Boolean,
) {
    companion object {
        val EMPTY = ClarityInfo(projectId = null, isLoggingEnabled = false)

        fun fromJson(json: JSONObject): ClarityInfo {
            val projectId = if (json.has("project_id") && !json.isNull("project_id")) {
                json.optString("project_id", "").takeIf { it.isNotEmpty() }
            } else null
            return ClarityInfo(
                projectId = projectId,
                isLoggingEnabled = json.optBoolean("is_logging_enabled", false),
            )
        }
    }
}
