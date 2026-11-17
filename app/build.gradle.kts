plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gigaconet.pdf"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gigaconet.pdf"
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }

    // Firma fija: cada versión nueva se instala encima de la anterior
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    // Motor PDF: editar, unir, rotar, proteger (Apache PDFBox para Android)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Escáner de documentos de Google (detecta bordes, recorta, filtros)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Reconocer texto de fotos y escaneos (OCR) sin internet
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
