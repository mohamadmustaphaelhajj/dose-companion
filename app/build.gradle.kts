plugins {
    id("com.android.application")
}

android {
    namespace = "com.dosecompanion.app"
    compileSdk = 35

    defaultConfig {
        // Instrumentation checks must use the isolated package, preserving the real journal.
        applicationId = if (providers.gradleProperty("reminderSmoke").orNull == "true")
            "com.dosecompanion.app.remindertest" else "com.dosecompanion.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
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
