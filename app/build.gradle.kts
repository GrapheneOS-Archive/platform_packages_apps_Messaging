import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    namespace = "com.android.messaging"

    defaultConfig {
        versionCode = 10001040 + 1
        versionName = "1.0.001"
        minSdk = 35
        targetSdk = 35

        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets.getByName("main") {
        assets.srcDir("../assets")
        manifest.srcFile("../AndroidManifest.xml")
        java.srcDirs("../src")
        res.srcDir("../res")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val useKeystoreProperties = keystorePropertiesFile.canRead()
    val keystoreProperties = Properties()
    if (useKeystoreProperties) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    if (useKeystoreProperties) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"),
                    "../proguard.flags", "../proguard-release.flags")
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Messaging d")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.palette:palette:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.guava:guava:33.4.0-android")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation(project(":lib:platform_external_libphonenumber"))
    implementation(project(":lib:platform_frameworks_ex:common"))
    implementation(project(":lib:platform_frameworks_opt_chips"))
    implementation(project(":lib:platform_frameworks_opt_colorpicker"))
    implementation(project(":lib:platform_frameworks_opt_photoviewer"))
    implementation(project(":lib:platform_frameworks_opt_vcard"))
}
