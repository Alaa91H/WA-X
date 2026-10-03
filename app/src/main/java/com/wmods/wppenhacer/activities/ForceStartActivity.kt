package com.wmods.wppenhacer.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.wmods.wppenhacer.xposed.core.FeatureLoader

class ForceStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra("pkg")
        if (packageName == FeatureLoader.PACKAGE_WPP || packageName == FeatureLoader.PACKAGE_BUSINESS) {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        }
        finish()
    }
}
