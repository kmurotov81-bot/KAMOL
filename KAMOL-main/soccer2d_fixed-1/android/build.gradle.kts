plugins { id("com.android.application"); kotlin("android") }

val natives by configurations.creating

android {
    namespace = "com.soccer.game"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.soccer.game"
        minSdk = 24
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].jniLibs.srcDirs("libs")
    // Kalit CI'da env orqali beriladi (repo'ga kalit qo'yilmaydi)
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
}

dependencies {
    val gdxVersion = "1.13.0"
    implementation(project(":shared"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    for (abi in listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-$abi")
        natives("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-$abi")
    }
}

// Native .so fayllarni libs/<abi>/ ga chiqarib beradi
tasks.register("copyAndroidNatives") {
    doFirst {
        natives.files.forEach { jar ->
            val abi = listOf("armeabi-v7a", "arm64-v8a", "x86_64", "x86")
                                .firstOrNull { jar.name.endsWith("natives-$it.jar") } ?: return@forEach
            copy {
                from(zipTree(jar))
                into(file("libs/$abi"))
                include("*.so")
            }
        }
    }
}
tasks.matching { it.name.contains("JniLibFolders") }.configureEach { dependsOn("copyAndroidNatives") }
