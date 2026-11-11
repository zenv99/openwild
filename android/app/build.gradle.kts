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
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "PACK_INDEX_URL", "\"$packIndexUrl\"")
        buildConfigField("String", "REPO_URL", "\"$repoUrl\"")
        buildConfigField("String", "LIGHTNING_ADDRESS", "\"$lightningAddress\"")
        buildConfigField("String", "FEEDBACK_EMAIL", "\"$feedbackEmail\"")
        buildConfigField("boolean", "SHOW_DONATIONS", "$showDonations")
    }

    buildTypes {
        release {
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
