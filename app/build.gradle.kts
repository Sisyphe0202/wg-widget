import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun runCmd(vararg args: String): String? = try {
    val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
    proc.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
} catch (e: Exception) { null }

val gitCommitCount: Int = runCmd("git", "rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitShortSha: String = runCmd("git", "rev-parse", "--short=7", "HEAD") ?: "dev"
val buildDate: String = SimpleDateFormat("yyyyMMdd").format(Date())

android {
    namespace = "com.example.wgwidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wgwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = gitCommitCount
        versionName = "$buildDate.$gitShortSha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
