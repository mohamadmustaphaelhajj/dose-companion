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
        versionCode = 2
        versionName = "0.2.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
