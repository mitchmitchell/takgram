import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Secrets and machine-specific paths never live in the repository. Lookup order for each key:
// environment variable, then local.properties, then ~/.takgram/secrets.properties.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val userSecrets = Properties().apply {
    val f = File(System.getProperty("user.home"), ".takgram/secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun setting(key: String, env: String): String? =
    System.getenv(env) ?: localProps.getProperty(key) ?: userSecrets.getProperty(key)

val apiId = setting("telegram.apiId", "TELEGRAM_API_ID") ?: "0"
val apiHash = setting("telegram.apiHash", "TELEGRAM_API_HASH") ?: ""
require(apiId.all { it.isDigit() }) { "telegram.apiId must be numeric" }
require(apiHash.all { it.isLetterOrDigit() }) { "telegram.apiHash must be alphanumeric" }

// Output of android/tdlib/build-tdlib.sh: <dir>/java (generated sources) and <dir>/libs/<abi>/libtdjni.so
val tdlibDir = file(setting("tdlib.dir", "TAKGRAM_TDLIB_DIR") ?: "../tdlib/out")

android {
    namespace = "org.takgram.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.takgram.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.0.3-a2"
        buildConfigField("int", "TG_API_ID", apiId)
        buildConfigField("String", "TG_API_HASH", "\"$apiHash\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug {
            // Debug builds talk to Telegram's test servers unless -Ptakgram.testDc=false is given.
            val testDc = (findProperty("takgram.testDc") as String?)?.toBoolean() ?: true
            buildConfigField("boolean", "TG_TEST_DC", testDc.toString())
        }
        release {
            buildConfigField("boolean", "TG_TEST_DC", "false")
            isMinifyEnabled = false
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDir(File(tdlibDir, "java"))
            jniLibs.srcDir(File(tdlibDir, "libs"))
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.annotation)
    implementation(libs.zxing.core)
}
