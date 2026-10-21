package com.soccer.game

import android.content.Context
import android.net.wifi.WifiManager

/** Ilova konteksti (LAN qidiruv uchun Wi-Fi multicast lock kerak). */
object AppCtx {
    @Volatile var app: Context? = null

    /** Ba'zi telefonlar Wi-Fi'da broadcast paketlarini quvvat tejash uchun o'tkazmaydi - lock buni oldini oladi */
    fun multicastLock(): WifiManager.MulticastLock? = try {
        val wm = app?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.createMulticastLock("soccer-lan")?.apply { setReferenceCounted(false); acquire() }
    } catch (_: Exception) { null }
}
