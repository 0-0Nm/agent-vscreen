package com.kilon.agentvscreen.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.kilon.agentvscreen.host.AppLogger
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [vscreen] Accurate on-device OCR: PaddleOCR PP-OCRv6 small (detection + recognition),
 * run with ONNX Runtime inside the app. Official ONNX exports:
 * huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx and ..._rec_onnx (Apache-2.0).
 *
 * Pipeline (mirrors PaddleOCR's DBPostProcess + CTCLabelDecode, simplified for screens,
 * where text is axis-aligned):
 *  1. det: resize so the long side is [DET_LONG] (multiples of 32), BGR, ImageNet mean/std,
 *     probability map -> threshold 0.2 -> connected components -> box score >= 0.45 ->
 *     axis-aligned box grown by the DB "unclip" distance (area * 1.4 / perimeter); the
 *     thresholds are the ones in the model's inference.yml.
 *  2. rec: crop each box from the full-resolution frame, height 48, (x/255-0.5)/0.5, BGR,
 *     batched by width; greedy CTC decode (class 0 = blank, last class = space).
 *  3. character boxes: each decoded character's CTC time steps give its centre; edges are
 *     the midpoints to the neighbours. Items are split where the gap between characters is
 *     wide (two labels inside one detected box), like the ML Kit path.
 *
 * Models live in assets/vscreen-ocr/ (fetched at build time by the porting kit).
 * Any failure is thrown to the caller, which falls back to ML Kit.
 */
internal object VScreenPaddleOcr {
    private const val TAG = "VScreenPaddleOcr"
    const val ENGINE = "pp-ocrv6-small (on-device, onnxruntime)"
    private const val DIR = "vscreen-ocr"
    /** v41: 1280 (was 960) — small game text on a 2800-wide screen is lost at 960. */
    private const val DET_LONG = 1280
    private const val DET_THRESH = 0.2f
    private const val BOX_THRESH = 0.45f
    private const val UNCLIP = 1.4f
    private const val REC_H = 48
    private const val REC_MAX_W = 3200
    /** Sum of padded widths per rec batch: the output is B x (W/8) x 18710 floats. */
    private const val REC_BATCH_W = 2400
    private const val REC_MIN_SCORE = 0.5f

    private class Engine(val env: OrtEnvironment, val det: OrtSession, val rec: OrtSession, val chars: Array<String>)

    @Volatile private var engine: Engine? = null
    @Volatile private var loadError: String? = null

    fun available(context: Context): Boolean = loadError == null &&
        runCatching { context.assets.open("$DIR/det.onnx").close(); true }.getOrDefault(false)

    fun loadError(): String? = loadError

    @Synchronized
    private fun engine(context: Context): Engine {
        engine?.let { return it }
        loadError?.let { throw IllegalStateException(it) }
        try {
            val env = OrtEnvironment.getEnvironment()
            fun session(name: String): OrtSession {
                val bytes = context.assets.open("$DIR/$name").use { it.readBytes() }
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(max(1, min(4, Runtime.getRuntime().availableProcessors() / 2)))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                return env.createSession(bytes, opts)
            }
            val dict = context.assets.open("$DIR/dict.txt").bufferedReader(Charsets.UTF_8).use { r ->
                r.readLines().filter { it.isNotEmpty() }
            }
            val chars = (listOf("") + dict + listOf(" ")).toTypedArray()
            return Engine(env, session("det.onnx"), session("rec.onnx"), chars).also { engine = it }
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            AppLogger.warning(TAG, "PP-OCRv6 load failed: $loadError")
            throw t
        }
    }

    /** Items in frame pixels. Throws on any failure. Not on the main thread. One reading at a time (v44: callers on several threads). */
    @Synchronized
    fun recognize(context: Context, frame: Bitmap): List<VScreenOcr.Item> {
        val e = engine(context)
        val boxes = detect(e, frame)
        if (boxes.isEmpty()) return emptyList()
        return recognizeBoxes(e, frame, boxes)
    }

    // ── detection ─────────────────────────────────────────────────────────

    private class DetBox(val crop: Rect, val tight: Rect)

    private fun detect(e: Engine, frame: Bitmap): List<DetBox> {
        val w = frame.width
        val h = frame.height
        val r = min(1f, DET_LONG.toFloat() / max(w, h))
        val rw = max(32, (w * r / 32f).roundToInt() * 32)
        val rh = max(32, (h * r / 32f).roundToInt() * 32)
        val small = Bitmap.createScaledBitmap(frame, rw, rh, true)
        val px = IntArray(rw * rh)
        small.getPixels(px, 0, rw, 0, 0, rw, rh)
        if (small !== frame) small.recycle()
        val plane = rw * rh
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            // BGR order, mean/std applied per channel position as PaddleOCR does.
            input[i] = (((c and 0xFF) / 255f) - 0.485f) / 0.229f
            input[plane + i] = ((((c shr 8) and 0xFF) / 255f) - 0.456f) / 0.224f
            input[2 * plane + i] = ((((c shr 16) and 0xFF) / 255f) - 0.406f) / 0.225f
        }
        val prob = FloatArray(plane)
        OnnxTensor.createTensor(e.env, FloatBuffer.wrap(input), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { t ->
            e.det.run(mapOf(e.det.inputNames.first() to t)).use { res ->
                val fb = (res.get(0) as OnnxTensor).floatBuffer
                fb.get(prob, 0, min(plane, fb.remaining()))
            }
        }
        // Connected components of the thresholded map (4-neighbour flood fill).
        val label = IntArray(plane)
        val queue = IntArray(plane)
        val sx = w.toFloat() / rw
        val sy = h.toFloat() / rh
        val out = ArrayList<DetBox>()
        var n = 0
        for (start in 0 until plane) {
            if (label[start] != 0 || prob[start] <= DET_THRESH) continue
            n++
            var qh = 0
            var qt = 0
            queue[qt++] = start
            label[start] = n
            var x0 = start % rw; var x1 = x0; var y0 = start / rw; var y1 = y0
            var sum = 0.0
            while (qh < qt) {
                val p = queue[qh++]
                val px0 = p % rw
                val py0 = p / rw
                sum += prob[p]
                if (px0 < x0) x0 = px0; if (px0 > x1) x1 = px0
                if (py0 < y0) y0 = py0; if (py0 > y1) y1 = py0
                if (px0 > 0) { val q = p - 1; if (label[q] == 0 && prob[q] > DET_THRESH) { label[q] = n; queue[qt++] = q } }
                if (px0 < rw - 1) { val q = p + 1; if (label[q] == 0 && prob[q] > DET_THRESH) { label[q] = n; queue[qt++] = q } }
                if (py0 > 0) { val q = p - rw; if (label[q] == 0 && prob[q] > DET_THRESH) { label[q] = n; queue[qt++] = q } }
                if (py0 < rh - 1) { val q = p + rw; if (label[q] == 0 && prob[q] > DET_THRESH) { label[q] = n; queue[qt++] = q } }
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (min(bw, bh) < 3 || sum / qt < BOX_THRESH) continue
            val d = bw * bh * UNCLIP / (2f * (bw + bh))
            val crop = Rect(
                ((x0 - d) * sx).toInt().coerceIn(0, w - 1), ((y0 - d) * sy).toInt().coerceIn(0, h - 1),
                ((x1 + 1 + d) * sx).toInt().coerceIn(1, w), ((y1 + 1 + d) * sy).toInt().coerceIn(1, h),
            )
            // The shrunk text kernel grown by half the unclip distance is close to the ink box.
            val tight = Rect(
                ((x0 - d * 0.5f) * sx).toInt().coerceIn(0, w - 1), ((y0 - d * 0.5f) * sy).toInt().coerceIn(0, h - 1),
                ((x1 + 1 + d * 0.5f) * sx).toInt().coerceIn(1, w), ((y1 + 1 + d * 0.5f) * sy).toInt().coerceIn(1, h),
            )
            if (crop.width() >= 2 && crop.height() >= 2) out += DetBox(crop, tight)
            if (out.size >= 1000) break
        }
        return out
    }

    // ── recognition ───────────────────────────────────────────────────────

    private class Crop(val det: DetBox, val data: FloatArray, val width: Int)

    private fun recognizeBoxes(e: Engine, frame: Bitmap, boxes: List<DetBox>): List<VScreenOcr.Item> {
        val crops = boxes.map { b ->
            val c = b.crop
            val nw = ceil(REC_H * c.width().toDouble() / c.height()).toInt().coerceIn(8, REC_MAX_W)
            val sub = Bitmap.createBitmap(frame, c.left, c.top, c.width(), c.height())
            val scaled = Bitmap.createScaledBitmap(sub, nw, REC_H, true)
            val px = IntArray(nw * REC_H)
            scaled.getPixels(px, 0, nw, 0, 0, nw, REC_H)
            if (scaled !== sub) scaled.recycle()
            if (sub !== frame) sub.recycle()
            val plane = nw * REC_H
            val data = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = px[i]
                data[i] = (p and 0xFF) / 127.5f - 1f
                data[plane + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
                data[2 * plane + i] = ((p shr 16) and 0xFF) / 127.5f - 1f
            }
            Crop(b, data, nw)
        }
        val order = crops.indices.sortedBy { crops[it].width }
        val items = ArrayList<VScreenOcr.Item>()
        var k = 0
        while (k < order.size) {
            // Widths only grow along [order]: take crops while count x widest stays in budget.
            var end = k + 1
            while (end < order.size && end - k < 8 && (end - k + 1) * crops[order[end]].width <= REC_BATCH_W) end++
            val batch = order.subList(k, end).map { crops[it] }
            k = end
            val mw = batch.maxOf { it.width }
            val input = FloatArray(batch.size * 3 * REC_H * mw)
            batch.forEachIndexed { bi, c ->
                for (ch in 0 until 3) for (y in 0 until REC_H) {
                    System.arraycopy(c.data, ch * REC_H * c.width + y * c.width, input,
                        ((bi * 3 + ch) * REC_H + y) * mw, c.width)
                }
            }
            OnnxTensor.createTensor(e.env, FloatBuffer.wrap(input),
                longArrayOf(batch.size.toLong(), 3, REC_H.toLong(), mw.toLong())).use { t ->
                e.rec.run(mapOf(e.rec.inputNames.first() to t)).use { res ->
                    val out = res.get(0) as OnnxTensor
                    val shape = out.info.shape
                    val steps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val buf = out.floatBuffer
                    batch.forEachIndexed { bi, c ->
                        items += decode(e, buf, bi * steps * classes, steps, classes, mw, c)
                    }
                }
            }
        }
        items.sortWith(compareBy<VScreenOcr.Item>({ it.box.top / 8 }, { it.box.left }))
        return items
    }

    private class Ch(val text: String, val centre: Float, val start: Float, val end: Float, val score: Float)

    /** Greedy CTC decode of one crop, split into items with character boxes. */
    private fun decode(e: Engine, buf: FloatBuffer, base: Int, steps: Int, classes: Int, mw: Int, c: Crop): List<VScreenOcr.Item> {
        val stride = mw.toFloat() / steps
        val usable = min(steps, ceil(c.width / stride).toInt() + 1)
        val chars = ArrayList<Ch>()
        var prev = 0
        var runStart = -1
        var runScore = 0f
        var runLen = 0
        fun close(tEnd: Int) {
            if (prev != 0 && runStart >= 0 && prev < e.chars.size) {
                val s = runStart * stride
                val en = tEnd * stride
                chars += Ch(e.chars[prev], (s + en) / 2f, s, en, runScore / runLen)
            }
        }
        for (t in 0 until usable) {
            var best = 0
            var bestV = -Float.MAX_VALUE
            val off = base + t * classes
            for (ci in 0 until classes) {
                val v = buf.get(off + ci)
                if (v > bestV) { bestV = v; best = ci }
            }
            if (best != prev) {
                close(t)
                prev = best
                runStart = t; runScore = 0f; runLen = 0
            }
            runScore += bestV; runLen++
        }
        close(usable)
        if (chars.isEmpty()) return emptyList()
        if (chars.map { it.score }.average() < REC_MIN_SCORE) return emptyList()

        // Crop pixels -> frame pixels.
        val crop = c.det.crop
        val kx = crop.width().toFloat() / c.width
        val tight = c.det.tight
        val top = tight.top
        val bottom = tight.bottom
        val textH = (bottom - top).coerceAtLeast(1)
        val ink = chars.filter { it.text.isNotBlank() }
        if (ink.isEmpty()) return emptyList()
        val centres = ink.map { crop.left + it.centre * kx }
        val gaps = centres.zipWithNext { a, b -> b - a }
        val pitch = if (gaps.isEmpty()) textH * 0.8f else gaps.sorted()[gaps.size / 2]
        val halfEdge = min(pitch, textH.toFloat()) / 2f
        val glyphs = ink.indices.map { i ->
            val l = if (i == 0) centres[i] - halfEdge else (centres[i - 1] + centres[i]) / 2f
            val r = if (i == ink.size - 1) centres[i] + halfEdge else (centres[i] + centres[i + 1]) / 2f
            VScreenOcr.Glyph(ink[i].text, Rect(max(crop.left.toFloat(), l).toInt(), top, min(crop.right.toFloat(), r).toInt(), bottom))
        }
        // Split at wide gaps (two labels in one detected box); spaces stay inside an item.
        val out = ArrayList<VScreenOcr.Item>()
        var from = 0
        fun flush(to: Int) {
            if (to <= from) return
            val part = ink.subList(from, to)
            val sb = StringBuilder()
            for (i in from until to) {
                if (i > from) {
                    // A decoded space between these two characters?
                    val a = ink[i - 1]
                    val b = ink[i]
                    if (chars.any { it.text.isBlank() && it.centre > a.centre && it.centre < b.centre }) sb.append(' ')
                }
                sb.append(ink[i].text)
            }
            val gs = glyphs.subList(from, to)
            val box = Rect(gs.first().box)
            gs.forEach { box.union(it.box) }
            if (part.isNotEmpty()) out += VScreenOcr.Item(sb.toString(), box, gs.toList())
            from = to
        }
        for (i in 1 until ink.size) {
            // Edge gap ~ centre gap - pitch; split when it exceeds ~0.5 x the ink height (ML Kit path: 0.6 x).
            if (centres[i] - centres[i - 1] > pitch + 0.52f * textH) flush(i)
        }
        flush(ink.size)
        return out
    }
}
