package com.kilon.agentvscreen.core

import android.content.Context
import android.content.pm.PackageManager
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * [vscreen] One path for app know-how, whichever memory tool the agent reaches for.
 *
 * Minis has two long-term stores: the main memory (memory_write / memory_get) and the per-app notes of the
 * background screen ([VScreenMap]: notes, saved segments, profile), shown when the app is launched. This object
 * makes both ends meet: the prompt carries a short index of apps with notes; memory_write entries that are clearly
 * app operating know-how are also filed as that app's note; memory_get also searches the notes.
 */
object VScreenKnowledge {
    private const val TAG = "VScreenKnowledge"

    private class Touch(val pkg: String, val atMs: Long)
    private val lastApps = HashMap<String, Touch>()
    private val labels = HashMap<String, String?>()
    private val journeys = HashMap<String, String>()

    private fun ctx(): Context? = BackgroundOperation.appContextOrNull()
    private fun enabled(): Boolean = runCatching { BackgroundOperation.state.value.enabled }.getOrDefault(false)

    // ── the app a session works on ───────────────────────────────────

    fun touch(sid: String?, pkg: String?) {
        if (sid.isNullOrEmpty() || pkg.isNullOrEmpty() || pkg == ctx()?.packageName) return
        synchronized(lastApps) { lastApps[sid] = Touch(pkg, System.currentTimeMillis()) }
    }

    /** The app [sid] launched: pages of other packages it leads to still belong to it (its notes, its pause steps). */
    fun startJourney(sid: String?, pkg: String?) {
        if (sid.isNullOrEmpty() || pkg.isNullOrEmpty() || pkg == ctx()?.packageName) return
        synchronized(journeys) { journeys[sid] = pkg }
        touch(sid, pkg)
    }
    fun endJourney(sid: String?) { if (sid != null) synchronized(journeys) { journeys.remove(sid) } }
    fun journey(sid: String?): String? = sid?.let { s -> synchronized(journeys) { journeys[s] } }

    /** The app [sid] last worked on within [withinMs]. */
    fun lastApp(sid: String?, withinMs: Long = 6 * 3600_000L): String? = sid?.let { s ->
        synchronized(lastApps) { lastApps[s] }?.takeIf { System.currentTimeMillis() - it.atMs <= withinMs }?.pkg
    }

    private fun lastAppAny(sids: List<String?>, withinMs: Long): String? = sids.firstNotNullOfOrNull { lastApp(it, withinMs) }

    // ── names ────────────────────────────────────────────────────────

    fun label(pkg: String): String? = synchronized(labels) {
        if (labels.containsKey(pkg)) return labels[pkg]
        val l = ctx()?.packageManager?.let { pm ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim() }.getOrNull()
        }?.takeIf { it.isNotEmpty() && it != pkg }
        labels[pkg] = l
        l
    }

    fun knownPackages(): Set<String> = runCatching { VScreenMap.packages().toCollection(LinkedHashSet()) }.getOrDefault(emptySet())

    /** A package name, or an app name the user would say (美团, Mini Metro). Null when nothing fits. */
    fun resolveApp(nameOrPkg: String?): String? {
        val q = nameOrPkg?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val c = ctx()
        if (q.contains('.') && (c == null || runCatching { c.packageManager.getApplicationInfo(q, 0); true }.getOrDefault(false) || q in knownPackages())) return q
        val lq = q.lowercase()
        val known = knownPackages()
        known.firstOrNull { p -> label(p)?.lowercase() == lq }?.let { return it }
        val pm = c?.packageManager ?: return null
        val launchable = runCatching {
            pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val apps = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrNull() ?: return null
        // v0.5: the names the user sees on the home screen are the launcher entries' labels (what `apps` lists), which
        // can differ from the application label (Honor's clock: launcher "时钟", application "时钟服务"…). Both are names.
        val launcherNames = runCatching {
            pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString().trim() }
        }.getOrDefault(emptyList())
        val named = launcherNames + apps.mapNotNull { ai -> runCatching { ai.packageName to pm.getApplicationLabel(ai).toString() }.getOrNull() }
            .sortedBy { if (it.first in launchable) 0 else 1 }
        return (named.firstOrNull { it.second.equals(q, ignoreCase = true) } ?: named.firstOrNull { it.second.lowercase().contains(lq) })?.first
    }

    private fun mentions(text: String, pkg: String): Boolean {
        val t = text.lowercase()
        if (t.contains(pkg.lowercase())) return true
        val l = label(pkg)?.lowercase() ?: return false
        return l.length >= 2 && t.contains(l)
    }

    // ── summaries ────────────────────────────────────────────────────

    fun summary(pkg: String): JSONObject? {
        val m = VScreenMap.load(pkg)
        if (m.empty()) return null
        val o = JSONObject().put("app", pkg).put("name", label(pkg) ?: JSONObject.NULL)
        if (m.notes.isNotEmpty()) o.put("notes", m.notes.size)
        if (m.segments.isNotEmpty()) o.put("segments", JSONArray(m.segments.map { it.name }))
        if (m.profile.isNotEmpty()) o.put("profile", JSONObject(m.profile as Map<*, *>))
        val last = (m.notes.map { it.at } + m.segments.map { it.at }).maxOrNull() ?: 0L
        if (last > 0) o.put("updated", last)
        return o
    }

    private fun summaryLine(o: JSONObject): String {
        val parts = ArrayList<String>()
        o.optInt("notes").takeIf { it > 0 }?.let { parts += "$it notes" }
        o.optJSONArray("segments")?.takeIf { it.length() > 0 }?.let { a -> parts += "segments " + (0 until a.length()).joinToString(",") { a.getString(it) } }
        o.optJSONObject("profile")?.let { p -> if (p.has("pause") && p.has("resume")) parts += "turn-based (pause/resume noted)" else if (p.optString("realtime") == "true") parts += "realtime" }
        val name = o.optString("name").takeIf { it.isNotEmpty() && it != "null" }
        return (if (name != null) "$name ${o.getString("app")}" else o.getString("app")) + if (parts.isEmpty()) "" else " (" + parts.joinToString("; ") + ")"
    }

    fun index(max: Int = 30): List<JSONObject> = knownPackages().mapNotNull { summary(it) }
        .sortedByDescending { it.optLong("updated") }.take(max)

    /** Notes / segments / app names matching all [keywords] (an app-name hit returns that app's notes). */
    fun search(keywords: String, max: Int = 12): JSONArray {
        val kws = keywords.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = JSONArray()
        if (kws.isEmpty()) return out
        for (pkg in knownPackages()) {
            val name = (label(pkg) ?: "").lowercase()
            fun isApp(k: String) = pkg.lowercase().contains(k) || (name.isNotEmpty() && (name.contains(k) || k.contains(name)))
            val appHit = kws.any { isApp(it) }
            val rest = kws.filterNot { isApp(it) }
            fun hit(text: String): Boolean { val t = text.lowercase(); return if (appHit) rest.all { t.contains(it) } || rest.isEmpty() else kws.all { t.contains(it) } }
            val m = VScreenMap.load(pkg)
            for (n in m.notes) if (hit(n.text))
                out.put(JSONObject().put("app", pkg).put("name", label(pkg) ?: JSONObject.NULL).put("note", n.id).put("text", n.text))
            for (s in m.segments) if (hit(s.name + " " + s.steps))
                out.put(JSONObject().put("app", pkg).put("segment", s.name).put("steps", s.steps))
            if (out.length() >= max) break
        }
        return out
    }

}
