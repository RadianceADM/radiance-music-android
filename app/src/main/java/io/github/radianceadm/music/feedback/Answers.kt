package io.github.radianceadm.music.feedback

import org.json.JSONObject

/** The five things rated, as the admin names them ([key]) and as the pop-up says them ([label]). */
enum class Area(val key: String, val label: String) {
    OVERALL("overall", "Overall"),
    SOUND("sound", "Sound"),
    MIXER("mixer", "Splitting & Mixer"),
    LYRICS("lyrics", "Lyrics"),
    SHARING("sharing", "Sharing"),
}

/**
 * What you said in the pop-up so far: enjoying it or not (null: not picked yet),
 * 1 to 5 stars for each [Area] (missing: not rated yet), and anything else you
 * wrote. Kept while you sign in, and after "Not now", until it's sent.
 */
data class Answers(
    val enjoying: Boolean? = null,
    val ratings: Map<Area, Int> = emptyMap(),
    val comment: String = "",
) {
    /** Enjoying picked and all five rated (the admin needs every one). */
    val complete get() = enjoying != null && missing.isEmpty()

    /** The areas not rated yet, in the pop-up's order. */
    val missing get() = Area.entries.filter { (ratings[it] ?: 0) !in 1..5 }

    fun rate(a: Area, stars: Int) = copy(ratings = ratings + (a to stars.coerceIn(1, 5)))

    fun write(text: String) = copy(comment = text.take(MAX_COMMENT))

    /** As the admin takes it (POST /api/music/feedback): a blank comment isn't sent. */
    fun json(appVersion: String): JSONObject {
        val stars = JSONObject()
        Area.entries.forEach { stars.put(it.key, ratings[it] ?: 0) }
        return JSONObject()
            .put("enjoying", enjoying == true)
            .put("ratings", stars)
            .apply { comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) } }
            .put("app_version", appVersion)
            .put("platform", "android")
    }

    companion object {
        /** The admin keeps up to this many characters. */
        const val MAX_COMMENT = 2000
    }
}
