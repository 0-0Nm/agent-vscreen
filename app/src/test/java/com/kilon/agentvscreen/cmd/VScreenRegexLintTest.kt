package com.kilon.agentvscreen.cmd

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [vscreen] Android's regex engine (ICU) refuses a bare `}` or `{` that the desktop JVM accepts, and a top-level
 * Regex that fails to compile kills the whole file's class (v43 crashed on every call this way). Unit tests run on
 * the JVM, so the source itself is checked: every brace in a Regex literal is escaped, a quantifier {n,m}, or in [..].
 */
class VScreenRegexLintTest {
    @Test fun regexLiteralsHaveNoBareBraces() {
        val roots = listOf("src/main/java/com/kilon/agentvscreen/cmd", "src/main/java/com/kilon/agentvscreen/core", "src/main/java/com/kilon/agentvscreen/api")
            .map { File(it) }.filter { it.isDirectory }
        assertTrue("source folders found", roots.isNotEmpty())
        val lit = Regex("Regex\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val bad = ArrayList<String>()
        for (root in roots) root.walk().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                for (m in lit.findAll(line)) {
                    val pattern = m.groupValues[1].replace("\\\\", "\\")        // Kotlin string → regex text
                    if (bareBrace(pattern)) bad += "${f.name}:${i + 1}  $pattern"
                }
            }
        }
        assertTrue("bare { or } in regex (ICU refuses them):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private fun bareBrace(p: String): Boolean {
        var i = 0; var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' && (p.getOrNull(i + 1) == 'p' || p.getOrNull(i + 1) == 'P') && p.getOrNull(i + 2) == '{' -> {
                    val end = p.indexOf('}', i + 3); if (end < 0) return true; i = end + 1; continue   // \p{L}
                }
                c == '\\' -> { i += 2; continue }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> { val q = Regex("^\\{\\d+(,\\d*)?}").find(p.substring(i)) ?: return true; i += q.value.length; continue }
                c == '}' -> return true
            }
            i++
        }
        return false
    }
}
