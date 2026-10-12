plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api("com.badlogicgames.gdx:gdx:1.12.1")
    api("com.badlogicgames.gdx:gdx-box2d:1.12.1")
}
