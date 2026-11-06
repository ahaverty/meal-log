import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun props(name: String) = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}

val keystoreProps = props("keystore.properties")
// Server settings baked into Alan's own build (secrets.properties is never committed); they can be
// changed in the app's Settings too.
val secrets = props("secrets.properties")
fun secret(k: String) = "\"" + (secrets.getProperty(k) ?: "").replace("\"", "") + "\""

val commitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace = "studio.alans.meallog"
    compileSdk = 35

    defaultConfig {
        applicationId = "studio.alans.meallog"
        minSdk = 31
        targetSdk = 35
        versionCode = 100 + commitCount
        versionName = "0.$commitCount"
        buildConfigField("String", "MEAL_URL", secret("MEAL_URL"))
        buildConfigField("String", "MEAL_TOKEN", secret("MEAL_TOKEN"))
        buildConfigField("String", "CF_ID", secret("CF_ID"))
        buildConfigField("String", "CF_SECRET", secret("CF_SECRET"))
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
