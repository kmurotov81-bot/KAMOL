package soccer.server

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Yomon tarmoqni simulyatsiya qiladi (paket yo'qolishi, kechikish, jitter).
 * Server tomonida ikkala yo'nalishga ham qo'llanadi: latencyMs HAR BIR yo'nalish uchun,
 * ya'ni RTT ~ 2 * latencyMs. Jitter tufayli paketlar tartibi ham buziladi (reorder).
 */
class NetSim(val lossPct: Float, val latencyMs: Long, val jitterMs: Long) {
    val enabled = lossPct > 0f || latencyMs > 0 || jitterMs > 0
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "netsim").apply { isDaemon = true }
    }

    fun deliver(task: Runnable) {
        if (!enabled) { task.run(); return }
        if (Random.nextFloat() * 100f < lossPct) return
        val jitter = if (jitterMs > 0) Random.nextLong(-jitterMs, jitterMs + 1) else 0L
        exec.schedule(task, maxOf(0L, latencyMs + jitter), TimeUnit.MILLISECONDS)
    }

    override fun toString() = "NetSim(loss=$lossPct%, latency=${latencyMs}ms/yo'nalish, jitter=±${jitterMs}ms)"
}
