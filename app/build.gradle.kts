plugins {
    id("com.android.application")
}

android {
    namespace = "br.com.rechi.mobile"
    buildFeatures {
        buildConfig = true
    }
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
            "\"${providers.gradleProperty("configurationApiBaseUrl").orElse("https://example.invalid/").get()}\""
        )
        buildConfigField(
            "String",
            "SERVER_JWT_PUBLIC_KEY_BASE64",
            "\"${providers.gradleProperty("serverJwtPublicKeyBase64").orElse("").get()}\""
        )
        buildConfigField(
            "String",
            "SERVER_JWT_ISSUER",
            "\"${providers.gradleProperty("serverJwtIssuer").orElse("rechi-mdm-api").get()}\""
        )
        buildConfigField(
            "String",
            "SERVER_JWT_AUDIENCE",
            "\"${providers.gradleProperty("serverJwtAudience").orElse("rechi-mdm-device").get()}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
