plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.p2pchatapplication"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.p2pchatapplication"
        minSdk = 27
        targetSdk = 36
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
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

//    buildFeatures {
//        viewBinding = true
//    }

}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    dependencies {
        implementation("androidx.appcompat:appcompat:1.6.1")
        implementation("com.google.android.material:material:1.11.0")
        implementation("androidx.constraintlayout:constraintlayout:2.1.4")
        implementation("androidx.recyclerview:recyclerview:1.3.2")

        // Room database
        implementation("androidx.room:room-runtime:2.6.1")
        annotationProcessor("androidx.room:room-compiler:2.6.1")

        // Google Nearby Connections API
        implementation("com.google.android.gms:play-services-nearby:19.0.0")

        // Lifecycle components
        implementation("androidx.lifecycle:lifecycle-viewmodel:2.7.0")
        implementation("androidx.lifecycle:lifecycle-livedata:2.7.0")

        // Concurrent utilities
        implementation("androidx.concurrent:concurrent-futures:1.1.0")

        // Gson for JSON serialization
        implementation("com.google.code.gson:gson:2.10.1")

        testImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
        androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    }

}