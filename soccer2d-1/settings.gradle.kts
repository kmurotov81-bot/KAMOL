pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "soccer"
include(":shared", ":server")
// Docker/CI'da faqat server kerak bo'lsa: SKIP_ANDROID=1
if (System.getenv("SKIP_ANDROID") == null) include(":android")
