package soccer.shared

/**
 * Umumiy log: server kodi (shared) ham, Android ilova ham shuni ishlatadi.
 * Android'da `sink` AppLog'ga ulanadi (log.txt); desktop serverda sink yo'q -> konsolga yoziladi.
 */
object SLog {
    @Volatile var sink: ((Char, String, String, Throwable?) -> Unit)? = null

    fun i(tag: String, msg: String) = out('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = out('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = out('E', tag, msg, t)

    private fun out(level: Char, tag: String, msg: String, t: Throwable?) {
        val s = sink
        if (s != null) { try { s(level, tag, msg, t) } catch (_: Throwable) {} }
        else { println("$level/$tag: $msg"); t?.printStackTrace() }
    }
}
