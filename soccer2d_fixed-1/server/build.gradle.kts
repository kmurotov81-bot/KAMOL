plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
application { mainClass.set("soccer.server.MainKt") }
dependencies {
    implementation(project(":shared"))
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:1.12.1:natives-desktop")
    runtimeOnly("com.badlogicgames.gdx:gdx-box2d-platform:1.12.1:natives-desktop")
}
