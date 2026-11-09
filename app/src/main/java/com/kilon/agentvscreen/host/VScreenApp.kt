package com.kilon.agentvscreen.host

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.core.BackgroundOperation

class VScreenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        ApiConfig.init(this)
        com.kilon.agentvscreen.api.AgentLedger.init(this)
        ShizukuManager.init(this)
        BackgroundOperation.init(this)
        if (Prefs.serviceOn(this)) HostService.start(this)
    }
}

/** Boot / app update: bring the service back when the user left it on. (Shizuku itself may still need a manual start.) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.serviceOn(context)) HostService.start(context)
    }
}
