package com.kilon.agentvscreen.api

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/** Port, access token and turn length; everything a caller needs to connect, in one place. */
object ApiConfig {
    private const val PREFS = "agent_vscreen_api"
    const val DEFAULT_PORT = 18765
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("token", null).isNullOrEmpty()) prefs.edit().putString("token", newToken()).apply()
        Turns.quietMs = quietMinutes * 60_000L
    }

    val port: Int get() = prefs.getInt("port", DEFAULT_PORT)
    val token: String get() = prefs.getString("token", null) ?: ""
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** Minutes of silence after which a caller's turn is over (screen idles). */
    var quietMinutes: Int
        get() = prefs.getInt("quiet_min", 3)
        set(v) { prefs.edit().putInt("quiet_min", v.coerceIn(1, 60)).apply(); Turns.quietMs = v.coerceIn(1, 60) * 60_000L }

    /** Other devices may connect over Tailscale (off by default; see ApiServer.startTailnet). */
    var tailnet: Boolean
        get() = prefs.getBoolean("tailnet", false)
        set(v) { prefs.edit().putBoolean("tailnet", v).apply() }

    fun setPort(p: Int) { prefs.edit().putInt("port", p.coerceIn(1024, 65535)).apply() }
    fun resetToken() { prefs.edit().putString("token", newToken()).apply() }

    /** One line that installs the `vscreen` command in Termux / a proot or chroot Linux / adb shell and stores the token. */
    fun installLine(): String = "curl -fsS $baseUrl/cli | sh -s -- install $token"

    /** MCP client config (streamable HTTP). */
    fun mcpJson(): String = """{
  "mcpServers": {
    "agent-vscreen": {
      "type": "http",
      "url": "$baseUrl/mcp",
      "headers": { "Authorization": "Bearer $token" }
    }
  }
}"""

    private fun newToken(): String {
        val abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..24).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }
}
