import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreProperties = Properties().apply {
    val secrets = file("keystore.properties")
    if (secrets.exists()) secrets.inputStream().use { load(it) }
}

fun keystoreSecret(envName: String, propName: String): String =
    System.getenv(envName) ?: keystoreProperties.getProperty(propName).orEmpty()

android {
    namespace = "net.solardepin.solarchik"
    compileSdk = 35
    layout.buildDirectory.set(file((project.findProperty("buildRoot") as String?) ?: "/tmp/solarchik-apk-build"))

    defaultConfig {
        // 1.0.0: Solarchik Assistant installs next to the Solarchik game build (net.solardepin.solarchik).
        applicationId = "net.solardepin.solarchik.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 112
        versionName = "1.1.2"
        buildConfigField("boolean", "MAINNET_PAID_MINT", "false")
        // The assistant build never talks to mainnet, Seeker included.
        buildConfigField("boolean", "DEVNET_ONLY", "false")
        // 1.1.0: mainnet Metaplex Core mints stay "coming soon" until the mainnet collection exists (scripts/mainnet/README.md).
        buildConfigField("boolean", "MAINNET_MINT_READY", "false")
        // Filled from scripts/mainnet/out/collection-mainnet.json once Vadym's funded key created the collection.
        buildConfigField("String", "MAINNET_COLLECTION", "\"\"")
        buildConfigField("String", "MAINNET_COLLECTION_AUTHORITY", "\"\"")
    }

    buildFeatures { buildConfig = true }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            // First-launch onboarding stays off in older screen tests; TodayTest switches it on.
            it.systemProperty("solarchik.onboarding", "0")
            // 1.1.0: the app defaults to English; the existing uk-qualifier tests run as "follow the phone"
            it.systemProperty("solarchik.langDefault", (project.findProperty("langDefault") as String?) ?: "phone")
            // 1.1.0: the app defaults to mainnet; the unit suite runs in dev devnet mode unless a test flips it.
            it.systemProperty("solarchik.cluster", (project.findProperty("cluster") as String?) ?: "devnet")
            // 1.1.0: read-only live mainnet checks (balances, Jupiter quote + unsigned swap build, memo simulation)
            it.systemProperty("solarchik.mainnetLive", (project.findProperty("mainnetLive") as String?) ?: "0")
            it.systemProperty("solarchik.rehearsal", (project.findProperty("rehearsal") as String?) ?: "")
            it.systemProperty("solarchik.devnet", (project.findProperty("devnet") as String?) ?: "0")
            it.systemProperty("solarchik.live", (project.findProperty("live") as String?) ?: "")
            it.systemProperty("solarchik.chat", (project.findProperty("chat") as String?) ?: "")
            it.systemProperty("solarchik.liveAgents", (project.findProperty("liveAgents") as String?) ?: "0")
            it.systemProperty("solarchik.liveWaitSec", (project.findProperty("liveWaitSec") as String?) ?: "40")
            it.systemProperty("solarchik.runshots", (project.findProperty("runshots") as String?) ?: layout.buildDirectory.dir("screens-run").get().asFile.path)
            it.systemProperty("solarchik.runvideo", (project.findProperty("runvideo") as String?) ?: "")
            it.systemProperty("solarchik.runlong", (project.findProperty("runlong") as String?) ?: "")
            it.systemProperty("solarchik.runlongFrom", (project.findProperty("runlongFrom") as String?) ?: "13500")
            it.systemProperty("solarchik.runlongTo", (project.findProperty("runlongTo") as String?) ?: "23500")
            it.systemProperty("solarchik.shots", (project.findProperty("shots") as String?) ?: layout.buildDirectory.dir("screens").get().asFile.path)
        }
    }

    signingConfigs {
        // Release key lives only on the build box (gitignored). Debug builds use the standard debug key.
        create("release") {
            storeFile = file("solarchik-release.jks")
            storePassword = keystoreSecret("SOLARCHIK_STORE_PASSWORD", "storePassword")
            keyAlias = "solarchik"
            keyPassword = keystoreSecret("SOLARCHIK_KEY_PASSWORD", "keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            // Unsigned release when the box keystore is absent (e.g. a fresh clone).
            signingConfig = if (file("solarchik-release.jks").exists()) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // clientlib-ktx 2.0.7 lists androidx.test.ext:junit-ktx as a runtime dependency (upstream packaging bug). It pulled
    // androidx.test core/monitor/services and the REORDER_TASKS permission into the release APK. MWA never uses them.
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.7") {
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.ext")
        exclude(group = "androidx.test.services")
    }
    implementation("org.sol4k:sol4k:0.5.14")
    // Ed25519 keypair from a seed (FreeAsset); already on the runtime classpath through sol4k.
    implementation("org.sol4k:tweetnacl:0.1.6")
    implementation("io.github.funkatronics:kborsh:0.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // 0.21.8: QR code for the secretary top-up link (pure Java, no Play services)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    // 0.21.7: installs src/main/baseline-prof.txt so ART compiles the run/UI code ahead of time
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
