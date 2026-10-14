package soccer.shared

object C {
    const val PORT = 7777
    const val TEAM_SIZE = 2            // har jamoada ko'pi bilan 2 o'yinchi
    const val MAX_PLAYERS = 4          // 2v2
    const val FIELD_W = 30f
    const val FIELD_H = 18f
    const val GOAL_W = 5f
    const val GOAL_DEPTH = 2f
    const val DT = 1f / 60f            // fixed timestep (server va client bir xil)
    const val SNAP_EVERY = 3           // 60Hz / 3 = 20Hz snapshot
    const val PLAYER_R = 0.8f
    const val BALL_R = 0.45f
    const val MAX_SPEED = 9f
    const val KICK_RANGE = 1.7f
    const val KICK_IMPULSE = 4.5f
    const val KICK_COOLDOWN_TICKS = 18 // 0.3s
    const val INTERP_MS = 100L         // remote entity interpolation kechikishi
    const val SPEED_TOLERANCE = 1.25f  // to'qnashuv itarishi uchun zaxira
    const val BALL_MAX_SPEED = 30f
    const val MAX_INPUTS_PER_SEC = 180 // 60Hz + zaxira (Wi-Fi uzilishidan keyingi burst legit o'yinchini jazolamasligi uchun)
    const val KICK_AFTER_VIOLATIONS = 20
    const val BAN_MS = 60_000L
    const val TIMEOUT_MS = 15_000L     // uzilgan o'yinchi o'rnini shuncha vaqt saqlaymiz (reconnect)
    const val MAX_ID = 64
    const val PROTOCOL = 5              // client va server versiyasi bir xil bo'lishi shart
    const val BOT_ID_BASE = 50         // server botlari id'si shundan boshlanadi (odamlar < 50)
    const val MIN_PLAYERS = 2
    const val COUNTDOWN_S = 3
    const val GOAL_PAUSE_S = 3
    const val MATCH_S = 120
    const val END_S = 45                // yakuniy hisob ekrani; PLAY AGAIN bosilmasa shundan keyin Select Teams
    const val MATCH_MIN = 1             // o'yin vaqti (SETTINGS): 1..15 daqiqa
    const val MATCH_MAX = 15
    const val CORNER_CUT = 2.2f         // maydon burchaklari kesilgan (to'p 90 gradusli burchakda tiqilib qolmasligi uchun)
    const val BALL_STUCK_TICKS = 180    // to'p devor yonida shuncha vaqt qimirlamasa, maydon ichiga qaytariladi (3 s)
}

/** O'yinchi harakati: SHOOT = kuchli zarba, PASS = eng yaqin/yo'nalishdagi sherikka uzatma */
object Act {
    const val NONE = 0
    const val SHOOT = 1
    const val PASS = 2
}

object State {
    const val LOBBY = 0
    const val COUNTDOWN = 1
    const val PLAYING = 2
    const val GOAL = 3
    const val END = 4
}

/** Zarba/pas quvvati darajalari (0..3). Tugmani qancha uzoq bosib tursangiz, shuncha kuchli. */
object KickPower {
    val SHOOT = floatArrayOf(0.8f, 1f, 1.35f, 1.8f)
    val PASS = floatArrayOf(0.85f, 1f, 1.2f, 1.45f)
}
