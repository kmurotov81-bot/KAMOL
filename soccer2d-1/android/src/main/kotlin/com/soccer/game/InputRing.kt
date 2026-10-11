package com.soccer.game

/** Tasdiqlanmagan input'lar uchun halqa bufer (Input obyektlarisiz, allocation yo'q). */
class InputRing(private val cap: Int = 256) {
    private val seqs = IntArray(cap)
    private val mxs = FloatArray(cap)
    private val mys = FloatArray(cap)
    private val actions = IntArray(cap)
    private var head = 0
    var size = 0
        private set

    fun clear() { head = 0; size = 0 }

    fun push(seq: Int, mx: Float, my: Float, action: Int) {
        if (size == cap) { head = (head + 1) % cap; size-- }
        val i = (head + size) % cap
        seqs[i] = seq; mxs[i] = mx; mys[i] = my; actions[i] = action
        size++
    }

    /** server tasdiqlagan (ack) gacha bo'lgan input'larni tashlaydi */
    fun dropUpTo(ack: Int) {
        while (size > 0 && seqs[head] <= ack) { head = (head + 1) % cap; size-- }
    }

    fun mx(i: Int) = mxs[(head + i) % cap]
    fun my(i: Int) = mys[(head + i) % cap]
    fun action(i: Int) = actions[(head + i) % cap]
}
