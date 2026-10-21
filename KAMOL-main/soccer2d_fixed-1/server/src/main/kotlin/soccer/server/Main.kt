package soccer.server

import com.badlogic.gdx.physics.box2d.Box2D
import com.badlogic.gdx.utils.GdxNativesLoader
import soccer.shared.C

/**
 * Argumentlar (hammasi ixtiyoriy):
 *   --loss=5       paket yo'qolishi, %
 *   --latency=100  kechikish, ms (har yo'nalishga)
 *   --jitter=30    jitter, +-ms
 *   --bots=3       lokal test botlari soni
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
    val sim = NetSim(
        arg("loss")?.toFloatOrNull() ?: 0f,
        arg("latency")?.toLongOrNull() ?: 0L,
        arg("jitter")?.toLongOrNull() ?: 0L
    )
    if (sim.enabled) println("Tarmoq simulyatsiyasi yoqilgan: $sim")

    GdxNativesLoader.load()
    Box2D.init()
    val server = GameServer(sim)
    repeat(arg("bots")?.toIntOrNull() ?: 0) { Bot(C.PORT, it).start() }
    server.run()
}
