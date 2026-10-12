package soccer.shared

/** Foydalanuvchi kiritgan server manzili: "192.168.1.5", "192.168.1.5:7777" yoki "server.example.com". */
object HostSpec {
    class Parsed(val host: String, val port: Int)

    fun parse(spec: String): Parsed? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        val i = s.lastIndexOf(':')
        if (i > 0 && s.indexOf(':') == i) {          // aynan bitta ':' -> host:port
            val p = s.substring(i + 1).trim().toIntOrNull() ?: return null
            if (p !in 1..65535) return null
            val h = s.substring(0, i).trim()
            return if (h.isEmpty()) null else Parsed(h, p)
        }
        return Parsed(s, C.PORT)
    }
}
