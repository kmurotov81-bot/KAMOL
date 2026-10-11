pluginManagement {
    repositories {
        google()
        maven {
            url = uri("https://repo1.maven.org/maven2")
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        maven {
            url = uri("https://repo1.maven.org/maven2")
        }
    }
}

rootProject.name = "soccer"

include(":shared", ":server")

if (System.getenv("SKIP_ANDROID") == null) {
    include(":android")
}
