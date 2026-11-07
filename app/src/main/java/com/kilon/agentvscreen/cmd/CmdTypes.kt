package com.kilon.agentvscreen.cmd

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/** One command call: the words after `vscreen`, and the caller's session (one session owns the screen at a time). */
data class NativeOffloadRequest(val argv: List<String>, val sessionId: String?)

/** What a command returns: exit code (0 ok, 1 refused/failed, 2 usage) and text. Pictures are referenced in the text as [ShotStore.REF]name. */
data class NativeOffloadResult(val exitCode: Int, val output: String)

fun interface NativeOffloadHandler {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult
}

/**
 * Pictures a command made. They are written once to files/shots and referenced in the text as `vscreen-shot://<name>`;
 * the transport decides what the caller sees: an http URL (plain HTTP), a downloaded local path (the CLI) or an inline
 * image (MCP). Only the newest [KEEP] are kept.
 */
object ShotStore {
    const val REF = "vscreen-shot://"
    private const val KEEP = 300
    val NAME = Regex("^vscreen_[A-Za-z0-9_-]+\\.jpg$")
    val IN_TEXT = Regex(Regex.escape(REF) + "(vscreen_[A-Za-z0-9_-]+\\.jpg)")

    fun dir(ctx: Context): File = File(ctx.filesDir, "shots").apply { mkdirs() }

    fun save(ctx: Context, bmp: Bitmap, tag: String): String? = runCatching {
        val d = dir(ctx)
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_-]"), "").take(24).ifEmpty { "shot" }
        val name = "vscreen_${safeTag}_${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}.jpg"
        FileOutputStream(File(d, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        d.listFiles()?.filter { it.name.startsWith("vscreen_") }?.sortedBy { it.lastModified() }
            ?.let { fs -> if (fs.size > KEEP) fs.take(fs.size - KEEP).forEach { it.delete() } }
        REF + name
    }.getOrNull()

    fun file(ctx: Context, name: String): File? = if (NAME.matches(name)) File(dir(ctx), name).takeIf { it.isFile } else null

    /** Names referenced in [text], in order of first appearance. */
    fun refs(text: String): List<String> = IN_TEXT.findAll(text).map { it.groupValues[1] }.distinct().toList()
}
