package com.clauderelay.app.data

import org.json.JSONArray
import org.json.JSONObject

data class SessionInfo(
    val id: String,
    val cwd: String?,
    val title: String,
    val lastPrompt: String?,
    val updatedAt: String,
    val running: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = SessionInfo(
            id = o.getString("id"),
            cwd = o.optStringOrNull("cwd"),
            title = o.optString("title", "(untitled)"),
            lastPrompt = o.optStringOrNull("lastPrompt"),
            updatedAt = o.optString("updatedAt", ""),
            running = o.optBoolean("running", false),
        )
    }
}

enum class BlockKind { TEXT, THINKING, TOOL_USE, TOOL_RESULT }

data class Block(
    val kind: BlockKind,
    val text: String,
    val name: String? = null,
    val isError: Boolean = false,
) {
    companion object {
        fun from(o: JSONObject): Block {
            val kind = when (o.optString("kind")) {
                "thinking" -> BlockKind.THINKING
                "tool_use" -> BlockKind.TOOL_USE
                "tool_result" -> BlockKind.TOOL_RESULT
                else -> BlockKind.TEXT
            }
            return Block(kind, o.optString("text", ""), o.optStringOrNull("name"), o.optBoolean("isError", false))
        }
    }
}

data class ChatMessage(
    val uuid: String,
    val ts: String?,
    val role: String, // "user" | "assistant"
    val blocks: List<Block>,
) {
    /** A user entry that only carries tool results (Claude's tool output), not something a person typed. */
    val isToolOutput: Boolean get() = role == "user" && blocks.all { it.kind == BlockKind.TOOL_RESULT }

    companion object {
        fun from(o: JSONObject) = ChatMessage(
            uuid = o.getString("uuid"),
            ts = o.optStringOrNull("ts"),
            role = o.optString("role", "assistant"),
            blocks = o.optJSONArray("blocks").mapObjects { Block.from(it) },
        )
    }
}

data class Project(val cwd: String, val updatedAt: String)

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key)

inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(f(it)) }
    return out
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}
