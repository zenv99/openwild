plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Project links and settings. Override any with -P<name>=... when building.
val repoUrl: String = (findProperty("repoUrl") as String?) ?: "https://github.com/zenv99/openwild"
val packIndexUrl: String = (findProperty("packIndexUrl") as String?) ?: "$repoUrl/releases/download/packs/index.json"
// Bitcoin Lightning address for tips.
val lightningAddress: String = (findProperty("lightningAddress") as String?) ?: "zenv9@cake.cash"
// App feedback inbox. Empty = feedback goes to GitHub issues.
val feedbackEmail: String = (findProperty("feedbackEmail") as String?) ?: ""
// Google Play restricts in-app donation links; build Play releases with -PshowDonations=false.
val showDonations: Boolean = ((findProperty("showDonations") as String?) ?: "true").toBoolean()

android {
    namespace = "org.openwild.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openwild.app"
        minSdk = 26
        targetSdk = 35
        // CI stamps every build with a rising number so updaters (Obtainium, F-Droid clients) see new versions.
        // versionCode stays on that run number. v0.7.89 shipped as 189, and later runs are higher,
        // so a 1.0 build still installs over it. The displayed name is 1.0.N (1.0.0-dev locally).
        val build = (System.getenv("OW_BUILD_NUMBER") ?: "0").toInt()
        versionCode = 100 + build
        val releaseName = System.getenv("OW_VERSION_NAME")
        versionName = if (!releaseName.isNullOrBlank()) releaseName else "1.0.0-dev"
        buildConfigField("String", "PACK_INDEX_URL", "\"$packIndexUrl\"")
        buildConfigField("String", "REPO_URL", "\"$repoUrl\"")
        buildConfigField("String", "LIGHTNING_ADDRESS", "\"$lightningAddress\"")
        buildConfigField("String", "FEEDBACK_EMAIL", "\"$feedbackEmail\"")
        buildConfigField("boolean", "SHOW_DONATIONS", "$showDonations")
        // The app has no INTERNET permission; keep network code paths switched off too.
        buildConfigField("boolean", "ALLOW_NETWORK", "true")
        // Photo inbox: users can send their plant photos (after a permission screen) to a private repo,
        // where a bot checks them. The key only allows writing files to that one repo. Empty = feature off,
        // and the app falls back to the GitHub upload form.
        val inboxRepo = (findProperty("inboxRepo") as String?) ?: "zenv99/openwild-inbox"
        val inboxToken = System.getenv("OW_INBOX_TOKEN") ?: ""
        buildConfigField("String", "INBOX_REPO", "\"$inboxRepo\"")
        buildConfigField("String", "INBOX_TOKEN", "\"$inboxToken\"")
        // Optional Pl@ntNet key for a private build. Empty in source and in CI.
        // A key typed in the app replaces this. Never commit a real key.
        val plantnetKey = (System.getenv("OW_PLANTNET_API_KEY") ?: (findProperty("plantnetApiKey") as String?) ?: "")
            .replace(Regex("[^A-Za-z0-9-]"), "")
        buildConfigField("String", "PLANTNET_API_KEY", "\"$plantnetKey\"")
    }

    signingConfigs {
        // Shared debug key committed to the repo so every CI build can be installed over the last one.
        // NOT for Play Store releases — create a private release key for that.
        getByName("debug") {
            storeFile = file("openwild-dev.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Private release key, supplied by CI from GitHub secrets (never committed). Without it (forks,
    // local builds) the release APK falls back to the public dev key and cannot update an official install.
    val releaseStore = System.getenv("OW_KEYSTORE")?.takeIf { file(it).exists() }
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = System.getenv("OW_STORE_PASSWORD")
            keyAlias = System.getenv("OW_KEY_ALIAS")
            keyPassword = System.getenv("OW_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Not debuggable, shrunk and obfuscated. Signed with the private release key when CI has it.
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "tflite"     // memory-map the model straight from the APK
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.litert)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
