package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * FridayMemory: Persistent Local Memory Core for FRIDAY AI Assistant.
 *
 * Stores Boss Anand's personal preferences, remembered facts, custom notes,
 * and recent conversation history completely locally on device.
 */
object FridayMemory {

    private const val PREFS_NAME = "friday_local_memory"
    private const val KEY_REMEMBERED_FACTS = "remembered_facts"
    private const val KEY_CONVERSATION_HISTORY = "conversation_history"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_PIN = "user_pin"

    private var sharedPreferences: SharedPreferences? = null

    fun initialize(context: Context) {
        if (sharedPreferences == null) {
            sharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    var userName: String
        get() = sharedPreferences?.getString(KEY_USER_NAME, "Boss Anand") ?: "Boss Anand"
        set(value) {
            sharedPreferences?.edit()?.putString(KEY_USER_NAME, value)?.apply()
        }

    var userPin: String
        get() = sharedPreferences?.getString(KEY_USER_PIN, "226263") ?: "226263"
        set(value) {
            sharedPreferences?.edit()?.putString(KEY_USER_PIN, value)?.apply()
        }

    /**
     * Saves a piece of information or fact for Boss Anand.
     * Example: "bike number", "9999" or "mom birthday", "12 October"
     */
    fun saveFact(key: String, value: String) {
        val facts = getAllFacts()
        facts.put(key.trim().lowercase(), value.trim())
        sharedPreferences?.edit()?.putString(KEY_REMEMBERED_FACTS, facts.toString())?.apply()
    }

    /**
     * Looks up a remembered fact by key or keyword.
     */
    fun getFact(keyword: String): String? {
        val facts = getAllFacts()
        val query = keyword.trim().lowercase()

        // 1. Direct match
        if (facts.has(query)) {
            return facts.optString(query)
        }

        // 2. Fuzzy search across keys
        val keys = facts.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (query.contains(k) || k.contains(query)) {
                return facts.optString(k)
            }
        }
        return null
    }

    /**
     * Returns all stored facts as a JSON object.
     */
    fun getAllFacts(): JSONObject {
        val raw = sharedPreferences?.getString(KEY_REMEMBERED_FACTS, "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /**
     * Formats all remembered facts into a readable prompt context for Gemini AI.
     */
    fun getFormattedMemoryForAi(): String {
        val facts = getAllFacts()
        if (facts.length() == 0) return "No custom memories stored yet."

        val sb = StringBuilder()
        val keys = facts.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            sb.append("- ").append(key).append(": ").append(facts.optString(key)).append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * Saves recent conversation turns for context retention.
     */
    fun appendConversation(speaker: String, text: String) {
        val raw = sharedPreferences?.getString(KEY_CONVERSATION_HISTORY, "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            if (array.length() > 20) {
                array.remove(0)
            }
            val item = JSONObject().apply {
                put("speaker", speaker)
                put("text", text)
                put("timestamp", System.currentTimeMillis())
            }
            array.put(item)
            sharedPreferences?.edit()?.putString(KEY_CONVERSATION_HISTORY, array.toString())?.apply()
        } catch (_: Exception) {}
    }

    /**
     * Clears all remembered facts.
     */
    fun clearMemory() {
        sharedPreferences?.edit()?.remove(KEY_REMEMBERED_FACTS)?.apply()
    }
}
