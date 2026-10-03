package com.wmods.wppenhacer.xposed.bridge.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BridgeService : Service() {
    override fun onCreate() {
        super.onCreate()
        HookBinder.initialize(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        HookBinder.initialize(this)
        return HookBinder
    }
}
