plugins {
    id("com.android.application")
}

val cloudReader = providers.gradleProperty("cloudExperiment").orNull == "true"

android {
    namespace = "com.dosecompanion.app"
    compileSdk = 35
    ndkVersion = "27.3.13750724"
    val extractedNdk = rootProject.file(".tools/extract/android-ndk-r27d")
    if (extractedNdk.exists()) ndkPath = extractedNdk.absolutePath

    defaultConfig {
        // Instrumentation checks must use the isolated package, preserving the real journal.
        applicationId = if (providers.gradleProperty("reminderSmoke").orNull == "true")
            "com.dosecompanion.app.remindertest" else if ((providers.gradleProperty("localExperiment").orNull == "true" || cloudReader))
            "com.dosecompanion.app.localexperiment" else "com.dosecompanion.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.7.0-cloud-experiment"
        manifestPlaceholders["applicationLabel"] = if (applicationId.orEmpty().endsWith("localexperiment")) "Dose Companion Lab"
            else if (applicationId.orEmpty().endsWith("remindertest")) "Dose Companion Tests" else "@string/app_name"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake {
            arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            val source = rootProject.file(".tools/extract/llama.cpp-f4f6f2e7db76e98c95cc9b7fc608ea669c806044")
            if (source.exists()) arguments += "-DFETCHCONTENT_SOURCE_DIR_LLAMA=${source.absolutePath}"
        } }
        buildConfigField("boolean", "CLOUD_READER_ENABLED", cloudReader.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main").manifest.srcFile(if (cloudReader) "src/cloud/AndroidManifest.xml" else "src/main/AndroidManifest.xml")
    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    implementation(project(":core"))
    implementation("org.opencv:opencv:4.12.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("com.google.code.gson:gson:2.14.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
