package com.kilon.agentvscreen.core

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [vscreen] v49: what the agent wrote down about one app, and nothing it did not write.
 *
 *  - notes     lessons in the agent's words (`note <文字>`), shown when the app is launched;
 *  - segments  saved `act` step lists (`act … --save NAME`, replayed with the step `use NAME`);
 *  - profile   how to operate the app, set explicitly: `pause` / `resume` (the steps that pause and resume
 *              a realtime app: with both, every act segment runs resume → steps → watch → pause),
 *              `realtime` (no pause available: a segment looks for 1 s by default), `orientation`
 *              (how the app really ran, learned at launch when its manifest declares nothing), `record`.
 *
 * Nothing here is learned behind the agent's back except the orientation (a fact the launch measures).
 * Screens, ways, rules, pause-key colours and page names of earlier builds are not read any more.
 * One JSON file per app under files/vscreen/maps.
 */
internal object VScreenMap {
    private const val TAG = "VScreenMap"
    const val MAX_NOTES = 30
    const val MAX_SEGMENTS = 30

    class Note(val id: Int, val text: String, val at: Long) {
        fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("at", at)
    }

    class Segment(val name: String, val steps: String, val at: Long, var uses: Int) {
        fun json(): JSONObject = JSONObject().put("name", name).put("steps", steps).put("at", at).put("uses", uses)
    }

    class AppMap(val pkg: String) {
        val notes = ArrayList<Note>()
        val segments = ArrayList<Segment>()
        val profile = LinkedHashMap<String, String>()
        var seq = 0
        fun empty() = notes.isEmpty() && segments.isEmpty() && profile.isEmpty()
    }

    @Volatile private var appContext: Context? = null
    private val cache = HashMap<String, AppMap>()

    fun init(context: Context) { if (appContext == null) appContext = context.applicationContext }

    private fun dir(): File? = appContext?.let { File(File(it.filesDir, "vscreen"), "maps").apply { mkdirs() } }
    private fun file(pkg: String): File? = dir()?.let { File(it, VScreenStore.safeName(pkg) + ".json") }
    private fun arr(o: JSONObject, k: String): List<JSONObject> = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    @Synchronized fun load(pkg: String): AppMap = cache[pkg] ?: read(pkg).also { cache[pkg] = it }

    private fun read(pkg: String): AppMap {
        val m = AppMap(pkg)
        val f = file(pkg)
        if (f != null && f.isFile) runCatching {
            val o = JSONObject(f.readText())
            m.seq = o.optInt("seq")
            arr(o, "notes").forEach { n -> m.notes += Note(n.getInt("id"), n.getString("text"), n.optLong("at")) }
            arr(o, "segments").forEach { s -> m.segments += Segment(s.getString("name"), s.getString("steps"), s.optLong("at"), s.optInt("uses")) }
            o.optJSONObject("profile")?.let { p -> p.keys().forEach { k -> PROFILE_KEYS[k]?.invoke(p.optString(k))?.let { v -> m.profile[k] = v } } }
            if (m.seq < (m.notes.maxOfOrNull { it.id } ?: 0)) m.seq = m.notes.maxOf { it.id }
        }.onFailure { AppLogger.warning(TAG, "load($pkg): ${it.message}") }
        return m
    }

    @Synchronized fun save(m: AppMap) {
        val f = file(m.pkg) ?: return
        runCatching {
            if (m.empty()) { f.delete(); return }
            f.writeText(JSONObject().put("package", m.pkg).put("seq", m.seq)
                .put("notes", JSONArray(m.notes.map { it.json() }))
                .put("segments", JSONArray(m.segments.map { it.json() }))
                .put("profile", JSONObject(m.profile as Map<*, *>)).toString(1))
        }.onFailure { AppLogger.warning(TAG, "save(${m.pkg}): ${it.message}") }
    }

    /** Every app with something written down. */
    fun packages(): List<String> {
        val out = LinkedHashSet<String>()
        dir()?.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            runCatching { JSONObject(f.readText()).getString("package") }.getOrNull()?.let { out += it }
        }
        return out.filter { !load(it).empty() }
    }

    // ── notes ────────────────────────────────────────────────────────

    @Synchronized fun notes(pkg: String): List<Note> = load(pkg).notes.toList()

    /** Add a note; the same text again only refreshes its time. Oldest dropped past [MAX_NOTES]. */
    @Synchronized fun addNote(pkg: String, text: String): Note {
        val m = load(pkg)
        val t = text.trim().take(400)
        m.notes.firstOrNull { it.text == t }?.let { old ->
            m.notes.remove(old); val n = Note(old.id, t, System.currentTimeMillis()); m.notes += n; save(m); return n
        }
        val n = Note(++m.seq, t, System.currentTimeMillis())
        m.notes += n
        while (m.notes.size > MAX_NOTES) m.notes.removeAt(0)
        save(m)
        return n
    }

    @Synchronized fun deleteNote(pkg: String, id: Int): Boolean {
        val m = load(pkg)
        return m.notes.removeAll { it.id == id }.also { if (it) save(m) }
    }

    // ── saved segments ───────────────────────────────────────────────

    @Synchronized fun segments(pkg: String): List<Segment> = load(pkg).segments.toList()

    @Synchronized fun segment(pkg: String, name: String): Segment? = load(pkg).segments.firstOrNull { it.name == name }

    @Synchronized fun saveSegment(pkg: String, name: String, steps: String): Segment {
        val m = load(pkg)
        m.segments.removeAll { it.name == name }
        val s = Segment(name, steps, System.currentTimeMillis(), 0)
        m.segments += s
        while (m.segments.size > MAX_SEGMENTS) m.segments.removeAt(0)
        save(m)
        return s
    }

    @Synchronized fun markUsed(pkg: String, name: String) {
        val m = load(pkg)
        m.segments.firstOrNull { it.name == name }?.let { it.uses++; save(m) }
    }

    @Synchronized fun deleteSegment(pkg: String, name: String): Boolean {
        val m = load(pkg)
        return m.segments.removeAll { it.name == name }.also { if (it) save(m) }
    }

    // ── profile ──────────────────────────────────────────────────────

    private fun bool(v: String) = when (v.lowercase()) { "true", "on", "1", "yes" -> "true"; "false", "off", "0", "no" -> "false"; else -> null }

    /** Keys a profile may hold, with a checker returning the normalised value or null (invalid). */
    val PROFILE_KEYS: Map<String, (String) -> String?> = mapOf(
        "pause" to { v -> v.trim().takeIf { it.isNotEmpty() && it.length <= 300 } },
        "resume" to { v -> v.trim().takeIf { it.isNotEmpty() && it.length <= 300 } },
        "realtime" to { v -> bool(v) },
        "record" to { v -> bool(v) },
        "orientation" to { v -> v.trim().lowercase().takeIf { it == "landscape" || it == "portrait" } },
    )

    @Synchronized fun profile(pkg: String): Map<String, String> = load(pkg).profile.toMap()

    /** Merge [changes] (an empty value removes the key). Returns the new profile. */
    @Synchronized fun setProfile(pkg: String, changes: Map<String, String>): Map<String, String> {
        val m = load(pkg)
        for ((k, v) in changes) if (v.isEmpty()) m.profile.remove(k) else m.profile[k] = v
        save(m)
        return m.profile.toMap()
    }

    /** For tests: forget what is cached. */
    @Synchronized fun clearCache() { cache.clear() }
}
