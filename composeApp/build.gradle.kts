plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mymav.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mymav.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // SDK native libs (libzlib_jni / libm2video) ship arm-only. Including
        // partial x86_64 libs makes the emulator pick x86_64 and fail dlopen.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":glasses-api"))
    implementation(project(":glasses-stub"))
    implementation(project(":glasses-mav2"))
    implementation(project(":miniapps:notify"))
    implementation(project(":miniapps:spotify"))
    implementation(project(":miniapps:conversate"))
    implementation(project(":miniapps:food"))
    implementation(project(":miniapps:shop"))
    implementation(project(":miniapps:identify"))
    implementation(project(":miniapps:football"))
    implementation(project(":miniapps:fitness"))
    implementation(project(":miniapps:mirror"))
    implementation(project(":miniapps:gallery"))
    implementation(project(":miniapps:tasks"))
    implementation(project(":miniapps:workouts"))
    implementation(project(":miniapps:memorysight"))
    implementation(project(":miniapps:money"))
    implementation(project(":miniapps:mymind"))
    implementation(project(":miniapps:saved"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}