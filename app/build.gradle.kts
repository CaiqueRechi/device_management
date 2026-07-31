plugins {
    id("com.android.application")
}

android {
    namespace = "br.com.rechi.mobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "br.com.rechi.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField(
            "String",
            "CONFIGURATION_API_BASE_URL",
            "\"${stringProperty("configurationApiBaseUrl", "https://rechi.net.br/")}\""
        )
        buildConfigField(
            "String",
            "SERVER_JWT_PUBLIC_KEY_BASE64",
            "\"${stringProperty("serverJwtPublicKeyBase64", "")}\""
        )
        buildConfigField(
            "String",
            "SERVER_JWT_ISSUER",
            "\"${stringProperty("serverJwtIssuer", "rechi-mdm-api")}\""
        )
        buildConfigField(
            "String",
            "SERVER_JWT_AUDIENCE",
            "\"${stringProperty("serverJwtAudience", "rechi-mdm-device")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

fun stringProperty(name: String, defaultValue: String): String {
    return providers.gradleProperty(name).orElse(defaultValue).get()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
