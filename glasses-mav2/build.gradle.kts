plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val useSdk = (findProperty("maverick.useSdk") as String?)?.toBooleanStrictOrNull()
    ?: false
val sdkVersion = (findProperty("maverick.sdkVersion") as String?) ?: "0.2.0"

android {
    namespace = "com.mymav.glasses.mav2"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        buildConfigField("boolean", "HAS_MAV2_SDK", useSdk.toString())
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            if (useSdk) {
                java.srcDir("src/sdk/java")
                assets.srcDir("src/sdk/assets")
            }
        }
    }
}

dependencies {
    implementation(project(":glasses-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    if (useSdk) {
        implementation("com.everysight.mav2:maverick-ai-sdk:$sdkVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}