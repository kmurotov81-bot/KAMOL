package com.soccer.game

import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration

class AndroidLauncher : AndroidApplication() {
    override fun onPause() { AppLog.i("App", "activity paused"); super.onPause() }
    override fun onResume() { super.onResume(); AppLog.i("App", "activity resumed") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.app = applicationContext
        AppLog.init(applicationContext)
        val config = AndroidApplicationConfiguration().apply {
            useAccelerometer = false
            useCompass = false
            useWakelock = true          // o'yin davomida ekran o'chmaydi
            useImmersiveMode = true
        }
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        initialize(SoccerGame(debug), config)
    }
}
