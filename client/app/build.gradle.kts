plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.configureEach {
    if (name.startsWith("assemble")) {
        doLast {
            layout.buildDirectory.dir("outputs/apk").get().asFile
                .walkTopDown()
                .filter { it.isFile && it.extension == "apk" && !it.name.startsWith("ipwcam") }
                .forEach { apk ->
                    apk.copyTo(File(apk.parentFile, "ipwcam-${android.defaultConfig.versionName}.apk"), overwrite = true)
                }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    // CameraX
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    // ML Kit Barcode Scanning
    implementation(libs.mlkit.barcode)
    // OkHttp for MJPEG streaming
    implementation(libs.okhttp)
    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
