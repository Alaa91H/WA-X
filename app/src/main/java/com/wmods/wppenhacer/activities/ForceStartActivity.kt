package com.wmods.wppenhacer.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ForceStartActivity : Activity() {

    private val allowedPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra("pkg")
        if (packageName != null && packageName in allowedPackages) {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        }
        finish()
    }
}
