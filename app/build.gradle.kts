plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

android {
    namespace = "com.example.epivalastor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.epivalastor"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
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
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule"
            excludes += "META-INF/groovy-release-info.properties"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // 1. Le BOM global mis à jour (qui remplace l'ancien 'libs.androidx.compose.bom')
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))

    // 2. Interface graphique et Material 3
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // 3. Cœur d'Android (en conservant tes variables existantes)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // 4. Moteur de l'application (Réseau, ICS, Coroutines)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.mnode.ical4j:ical4j:3.2.14")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Synchro automatique en arrière-plan
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // 5. Base de données locale (Room)
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    ksp("androidx.room:room-compiler:$room_version")
    implementation("androidx.room:room-ktx:$room_version")

    // 6. Tests et Debug
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}