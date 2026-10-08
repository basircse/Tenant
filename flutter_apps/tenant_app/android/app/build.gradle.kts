import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.revesoft.tms_tenant"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.revesoft.tms_tenant"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // Release key shared by the admin and tenant apps: flutter_apps/keys/release.properties
    // (not in git). Without it, release builds are signed with this computer's debug key.
    val releaseKey = rootProject.file("../../keys/release.properties")
    signingConfigs {
        if (releaseKey.exists()) {
            val key = Properties().apply { releaseKey.inputStream().use { load(it) } }
            create("release") {
                storeFile = releaseKey.parentFile.resolve(key.getProperty("storeFile"))
                storePassword = key.getProperty("storePassword")
                keyAlias = key.getProperty("keyAlias")
                keyPassword = key.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

// Debug builds: let phones on USB reach the API on this computer at http://localhost:8980
// (the app's default on a real phone). Same as running `adb reverse tcp:8980 tcp:8980` for every
// connected device; it is lost whenever the cable is unplugged, so it runs after each debug build.
val adbExecutable = android.sdkDirectory
    .resolve("platform-tools/" + if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb")
    .absolutePath
val adbReverseApiPort by tasks.registering {
    val adb = adbExecutable
    doLast {
        fun adb(vararg args: String): String = try {
            val p = ProcessBuilder(adb, *args).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        } catch (e: Exception) {
            ""
        }
        adb("devices").lines().drop(1)
            .map { it.split('\t') }
            .filter { it.size == 2 && it[1].trim() == "device" }
            .forEach { (serial, _) ->
                adb("-s", serial, "reverse", "tcp:8980", "tcp:8980")
                logger.lifecycle("adb reverse tcp:8980 -> this computer on $serial")
            }
    }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(adbReverseApiPort) }
