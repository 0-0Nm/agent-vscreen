package com.kilon.agentvscreen.core

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONObject

/**
 * [vscreen] v18: per-app resolution downscaling that crops apps on the background screen.
 *
 * Evidence (Honor PTP-AN00, MagicOS 11, Mini Metro on a 2800x1264 virtual display): the window gets
 * the Honor APS compat ratio (apsResolutionRatio 0.8544, mGlobalScale 1.1704, mCompatFrame 2392x1080)
 * while the game's SurfaceView still queues full 2800x1264 buffers; SurfaceFlinger samples only
 * sourceCrop 2392x1080 of them and scales that up, so the right ~408px and bottom ~184px never reach
 * the screen. Android's own switches (am compat DOWNSCALED, cmd game downscale) are a different layer
 * and leave the APS ratio at 0.8544.
 *
 * Fix: through the shell-uid UserService, read the app's APS state, snapshot it (ratio + whether an
 * explicit per-app entry existed + that entry, parceled) into prefs, set the static ratio to 1.0, and
 * verify by reading it back. The snapshot survives a Minis crash; it is restored (entry re-set, or
 * deleted when there was none, then the ratio read back against the original) when the screen is
 * released, or on the next start when no screen is left. The APS ratio is per app, so the main-screen
 * copy of the same app also renders at full resolution until it is restored; results say so.
 *
 * Whether it actually worked is judged from the system's own state after the cold start
 * (mGlobalScale and the SurfaceView sourceCrop vs buffer size), never from exit codes.
 */
internal object VScreenDownscale {
    private const val TAG = "VScreenDownscale"
    private const val PREFS = "vscreen_downscale"
    private const val K_APS = "aps_snapshot_"
    private val apsLock = Any()

    @Volatile private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }
    private fun prefs() = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun fmt(f: Float) = "%.4f".format(f).trimEnd('0').trimEnd('.')

    data class Aps(val available: Boolean, val resolution: Float, val dynamic: Float, val hasInfo: Boolean,
                   val info: ByteArray?, val error: String? = null) {
        fun toJson(): JSONObject = if (!available) JSONObject().put("available", false).put("reason", error ?: "本机没有 aps_service")
            else JSONObject().put("available", true).put("resolution", resolution.toDouble()).put("dynamic_ratio", dynamic.toDouble())
                .put("explicit_app_entry", hasInfo)
    }

    fun apsRead(pkg: String): Aps = VScreenRemoteDisplay.apsGet(pkg)
        .getOrElse { Aps(false, 1f, 1f, false, null, it.message) }

    /**
     * v27: what `screen fit` can honestly promise for [pkg] on this phone. Never claims a cold start
     * that will not happen (Honor APS writes are refused without the system permission).
     */
    fun fitHint(pkg: String): String {
        deniedRc?.let { return "本机无法关闭它的降分辨率（荣耀 APS 无写权限，本次已试过）；screen fit 会改用副屏逻辑尺寸适配（wmsize_fit，冷启动一次）。根治办法是用户手动：" + VScreenShell.downscaleSettingHint() }
        val v = VScreenShell.device().vendor
        val aps = runCatching { apsRead(pkg).available }.getOrDefault(false)
        if ((v == "honor" || v == "huawei") && aps)
            return "screen fit 会先尝试改 APS 比例（这类机型通常没有写权限）；改不了就把副屏逻辑尺寸设成它的实际渲染尺寸并冷启动一次（wmsize_fit，画面整体等比缩放、不再裁边）。根治办法是用户手动：" + VScreenShell.downscaleSettingHint()
        return "screen fit 会尝试关闭它的降分辨率；只有设置确实改成了才冷启动一次，改不了就不重启"
    }

    enum class Status { CHANGED, ALREADY_FULL, DENIED, UNSUPPORTED, FAILED }
    data class Outcome(val status: Status, val text: String) { val changed get() = status == Status.CHANGED }

    /** Framework constants (android.aps): 0 ok, -1 invalid param, -3 no permission. */
    private const val APS_NO_PERMISSION = -3
    /** Set once the system said no: the shell (Shizuku/ADB) uid lacks com.hihonor.aps.permission.UPDATE_APS_INFO. */
    @Volatile private var deniedRc: Int? = null

    private fun deniedText(rc: Int) = "荣耀APS：系统拒绝写入（返回 $rc${if (rc == APS_NO_PERMISSION) " = APS_NO_PERMISSION" else ""}）。" +
        "改这个设置需要系统签名权限 com.hihonor.aps.permission.UPDATE_APS_INFO，Shizuku（ADB 模式）拿不到；未改任何设置，也没有重启应用"

    /** Honor/Huawei APS: render [pkg] at full resolution (ratio 1.0), keeping the original to restore. */
    fun apsDisable(pkg: String): Outcome = synchronized(apsLock) {
        val a = apsRead(pkg)
        if (!a.available) return Outcome(Status.UNSUPPORTED, "荣耀APS：不适用（${a.error ?: "本机没有该服务"}）")
        if (a.resolution >= 0.999f) return Outcome(Status.ALREADY_FULL, "荣耀APS：$pkg 的分辨率比例已是 ${fmt(a.resolution)}，缩放来自别的层")
        deniedRc?.let { return Outcome(Status.DENIED, deniedText(it)) }
        val p = prefs() ?: return Outcome(Status.FAILED, "荣耀APS：未初始化，未改")
        val fresh = !p.contains(K_APS + pkg)
        // Keep the first snapshot: it is the user's original, a later one would only record our 1.0.
        if (fresh) {
            val j = JSONObject().put("res", a.resolution.toDouble()).put("dyn", a.dynamic.toDouble()).put("has_info", a.hasInfo)
                .put("info", a.info?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL)
                .put("at", System.currentTimeMillis())
            if (!p.edit().putString(K_APS + pkg, j.toString()).commit()) return Outcome(Status.FAILED, "荣耀APS：保存原设置失败，未改")
        }
        val res = VScreenRemoteDisplay.apsSet(pkg, 1.0f)
        val rc = res.getOrNull()?.first
        val rb = res.getOrNull()?.second
        if (rc == 0 && rb != null && rb >= 0.999f)
            return Outcome(Status.CHANGED, "荣耀APS：$pkg 分辨率比例 ${fmt(a.resolution)} → ${fmt(rb)}（已读回确认；主屏上的同一应用也按全分辨率运行，释放副屏时恢复原设置）")
        // Not applied. If the state is exactly the original, nothing to undo: drop our snapshot.
        val now = apsRead(pkg)
        val untouched = now.available && kotlin.math.abs(now.resolution - a.resolution) < 0.002f && now.hasInfo == a.hasInfo
        val undo = if (untouched) { if (fresh) p.edit().remove(K_APS + pkg).commit(); "设置未被改动" } else (apsRestoreLocked(pkg) ?: "已尝试还原")
        if (rc != null && rc != 0 && (rb == null || kotlin.math.abs(rb - a.resolution) < 0.002f)) {
            if (rc == APS_NO_PERMISSION) deniedRc = rc
            return Outcome(Status.DENIED, deniedText(rc) + "（$undo）")
        }
        return Outcome(Status.FAILED, "荣耀APS：写入没有生效（返回 ${rc ?: res.exceptionOrNull()?.message}，读回 ${rb?.let { fmt(it) } ?: "?"}）；$undo")
    }

    /** Put back the snapshot for [pkg]; null when there was nothing to restore. */
    fun apsRestore(pkg: String): String? = synchronized(apsLock) { apsRestoreLocked(pkg) }

    private fun apsRestoreLocked(pkg: String): String? {
        val p = prefs() ?: return null
        val s = p.getString(K_APS + pkg, null) ?: return null
        val j = runCatching { JSONObject(s) }.getOrNull() ?: return "荣耀APS：$pkg 的原设置快照损坏，保留不动，未写入"
        val orig = j.optDouble("res", 1.0).toFloat()
        val has = j.optBoolean("has_info", false)
        val info = if (j.isNull("info")) null else runCatching { Base64.decode(j.getString("info"), Base64.NO_WRAP) }.getOrNull()
        return VScreenRemoteDisplay.apsRestore(pkg, has, info, orig).fold(
            { r ->
                // Restored = same ratio AND the same "explicit per-app entry exists or not" as before.
                if (kotlin.math.abs(r.readBack - orig) < 0.002f && r.hasEntry == has) {
                    p.edit().remove(K_APS + pkg).commit()
                    "荣耀APS：$pkg 已恢复为 ${fmt(r.readBack)}" + if (has) "（原应用配置）" else "（无单独配置，系统默认）"
                } else "荣耀APS：$pkg 恢复后读回 ${fmt(r.readBack)}、单独配置${if (r.hasEntry) "存在" else "不存在"}，" +
                    "原来是 ${fmt(orig)}、${if (has) "存在" else "不存在"}（${r.detail}）；保留快照，下次释放副屏时再试"
            },
            { "荣耀APS：$pkg 恢复失败（${it.message}），保留快照，下次释放副屏时再试" })
            .also { AppLogger.info(TAG, it) }
    }

    /** Packages whose original APS setting is still saved (not yet restored). */
    fun pending(): List<String> = runCatching {
        prefs()?.all?.keys?.filter { it.startsWith(K_APS) }?.map { it.removePrefix(K_APS) }.orEmpty()
    }.getOrDefault(emptyList())

    fun restoreAll(): List<String> = pending().mapNotNull { runCatching { apsRestore(it) }.getOrNull() }

    // ── what the compositor really shows ─────────────────────────────────

    /**
     * One SurfaceView layer of the app: its buffer (and valid content inside it, which excludes stride
     * padding), the part of it the compositor samples, and where that lands on the display.
     */
    data class Crop(val layerId: Long, val refW: Int, val refH: Int, val srcL: Double, val srcT: Double,
                    val srcW: Double, val srcH: Double, val dispW: Int, val dispH: Int) {
        /**
         * The crop bug: sampling starts at the top-left corner and keeps the same fraction of width and
         * height (the compat ratio), so the right/bottom part never shows. A centred crop (video
         * letterbox / fill) or a one-sided difference is a legitimate scaling choice, not this bug.
         */
        val cropped: Boolean get() {
            if (refW <= 0 || refH <= 0) return false
            val fx = srcW / refW; val fy = srcH / refH
            return srcL <= 2.0 && srcT <= 2.0 && fx < 0.97 && fy < 0.97 && kotlin.math.abs(fx - fy) < 0.03
        }
        fun describe(): String = "图层 #$layerId：内容 ${refW}x$refH，合成采样 ${srcW.toInt()}x${srcH.toInt()}" +
            (if (srcL > 2 || srcT > 2) " 自 (${srcL.toInt()},${srcT.toInt()})" else "") + " → 显示 ${dispW}x$dispH" +
            if (cropped) "（右侧 ${refW - srcW.toInt()}px、下方 ${refH - srcH.toInt()}px 没显示）" else "（未见左上锚定裁切）"
    }

    private val hdrLayer = Regex("^\\s*(?:\\*\\s*)?Layer \\[(\\d+)]\\s*(.*)$")
    private val hdrOldLayer = Regex("^\\s*\\+ \\w*Layer \\((.*)\\)")
    private val hdrOutput = Regex("^\\s*- Output Layer\\b(.*)$")
    private val idRe = Regex("#(\\d+)\\)?\\s*\\)?\\s*$")
    private val rect4 = "\\[\\s*(-?[\\d.]+)[\\s,]+(-?[\\d.]+)[\\s,]+(-?[\\d.]+)[\\s,]+(-?[\\d.]+)\\s*]"
    private val bufRe = Regex("geomBufferSize=$rect4")
    private val contentRe = Regex("geomContentCrop=$rect4")
    private val srcRe = Regex("sourceCrop=$rect4")
    private val dispRe = Regex("displayFrame=$rect4")

    private fun rect(m: MatchResult) = m.groupValues.drop(1).take(4).map { it.toDouble() }

    /** Parse a `dumpsys SurfaceFlinger` text; null when the app has no matching SurfaceView data. */
    fun parseCrop(sf: String, pkg: String): Crop? {
        class L(val name: String) { var buf: List<Double>? = null; var content: List<Double>? = null }
        class O(val id: Long) { var src: List<Double>? = null; var disp: List<Double>? = null }
        val layers = HashMap<Long, L>()
        val outs = ArrayList<O>()
        var curL: L? = null
        var curO: O? = null
        for (line in sf.lineSequence()) {
            hdrLayer.find(line)?.let { m ->
                val l = L(m.groupValues[2]); layers[m.groupValues[1].toLong()] = l; curL = l; curO = null; return@let
            } ?: hdrOldLayer.find(line)?.let { m ->
                val id = idRe.find(m.groupValues[1].trimEnd(')'))?.groupValues?.get(1)?.toLongOrNull()
                curO = null
                curL = if (id != null) L(m.groupValues[1]).also { layers[id] = it } else null
            } ?: hdrOutput.find(line)?.let { m ->
                curL = null
                curO = idRe.find(m.groupValues[1].trim())?.groupValues?.get(1)?.toLongOrNull()?.let { O(it).also { o -> outs += o } }
            } ?: run {
                // A section header (no indentation) ends the current block.
                if (line.isNotEmpty() && !line[0].isWhitespace()) { curL = null; curO = null }
                curL?.let { l ->
                    if (l.buf == null) bufRe.find(line)?.let { l.buf = rect(it) }
                    if (l.content == null) contentRe.find(line)?.let { l.content = rect(it) }
                }
                curO?.let { o ->
                    if (o.src == null) srcRe.find(line)?.let { o.src = rect(it) }
                    if (o.disp == null) dispRe.find(line)?.let { o.disp = rect(it) }
                }
            }
        }
        val ours = layers.filter { (_, l) -> l.name.contains("SurfaceView") && l.name.contains(pkg) && !l.name.contains("Background for") && l.buf != null }
        val found = outs.mapNotNull { o ->
            val l = ours[o.id] ?: return@mapNotNull null
            val src = o.src ?: return@mapNotNull null
            val disp = o.disp ?: return@mapNotNull null
            val b = l.buf!!
            val c = l.content?.takeIf { it[2] - it[0] > 0 && it[3] - it[1] > 0 }
            val refW = ((c ?: b)[2] - (c ?: b)[0]).toInt(); val refH = ((c ?: b)[3] - (c ?: b)[1]).toInt()
            if (refW <= 0 || refH <= 0) return@mapNotNull null
            Crop(o.id, refW, refH, src[0], src[1], src[2] - src[0], src[3] - src[1], (disp[2] - disp[0]).toInt(), (disp[3] - disp[1]).toInt())
        }
        return found.maxByOrNull { it.dispW.toLong() * it.dispH }
    }

    /** Null = unknown (no SurfaceView, or the dump was not understood): never read it as "fine". */
    fun surfaceCrop(pkg: String): Crop? = runCatching {
        parseCrop(VScreenShell.run(arrayOf("dumpsys", "SurfaceFlinger"), 15_000L).combined, pkg)
    }.getOrNull()

    /** Read-only vendor scaling hints (services and settings keys) for the probe, any maker. */
    fun vendorScalingDiag(): JSONObject {
        val svc = VScreenShell.run(arrayOf("service", "list"), 8_000L).combined.lines()
            .filter { Regex("(?i)\\baps|resolution|superres|game|vivo|vgc|vtouch").containsMatchIn(it) }.map { it.trim().take(160) }.take(20)
        val keys = Regex("(?i)resolution|downscale|game_|superres|apsres|smart_res|screen_res")
        val settings = listOf("global", "system", "secure").flatMap { ns ->
            VScreenShell.run(arrayOf("settings", "list", ns), 8_000L).combined.lines()
                .filter { keys.containsMatchIn(it.substringBefore('=')) }.map { "$ns:${it.trim().take(160)}" }
        }.take(30)
        return JSONObject().put("services", org.json.JSONArray(svc)).put("settings", org.json.JSONArray(settings))
    }

    // ── late downscaling (splash fine, the game page gets scaled seconds later) ──

    private val token = HashMap<Int, Long>()
    private val notes = HashMap<Int, String>()
    fun takeNote(displayId: Int): String? = synchronized(notes) { notes.remove(displayId) }
    private fun note(displayId: Int, s: String) { synchronized(notes) { notes[displayId] = s }; AppLogger.info(TAG, s) }

    /**
     * After a launch, look again at 4–60 s. Splash screens are often drawn at full size and the game page
     * is scaled seconds later. Only detects and reports (in the next tool result): fixing restarts the
     * app, so it is left to `screen fit`, which runs under the agent's screen lease.
     */
    fun watchAfterLaunch(d: VScreenDisplay, pkg: String) {
        val my = SystemClock.elapsedRealtimeNanos()
        synchronized(token) { token[d.displayId] = my }
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            fun alive() = !d.released && synchronized(token) { token[d.displayId] == my } &&
                BackgroundOperation.currentDisplayId() == d.displayId
            for (at in longArrayOf(4_000, 10_000, 20_000, 35_000, 60_000)) {
                val wait = t0 + at - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                if (!alive() || VScreenShell.topPackage(d.displayId) != pkg) return@Thread
                val gs = VScreenShell.windowScale(d.displayId, pkg)
                val crop = surfaceCrop(pkg)
                if (!((gs != null && gs > 1.02) || crop?.cropped == true)) continue
                val what = "compat ${gs?.let { "%.2f".format(it) } ?: "?"} 倍" + (crop?.let { "，${it.describe()}" } ?: "")
                note(d.displayId, "$pkg 启动约 ${at / 1000} 秒后被系统缩放（$what），副屏右侧/下方会被裁。" + fitHint(pkg))
                return@Thread
            }
        }.apply { isDaemon = true; name = "vscreen-scale-watch" }.start()
    }
}
