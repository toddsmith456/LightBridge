plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "dev.youniversal.theme"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Libraries ship unobfuscated; the consuming app decides how to shrink.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The BOM is exposed so consuming apps resolve the exact same Compose versions.
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)

    // WindowCompat powers edge-to-edge system bar handling.
    implementation(libs.androidx.core.ktx)

    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // The color engine is pure Kotlin, so its tests run on the JVM.
    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])

                groupId = "dev.youniversal"
                artifactId = "youniversal-theme"
                version = libs.versions.youniversal.get()

                pom {
                    name.set("Youniversal")
                    description.set(
                        "A drop-in Jetpack Compose theme system with Dark, Light and Cream " +
                            "background themes, full Material You (dynamic color) support and a " +
                            "polished, rounded component set.",
                    )
                    url.set("https://github.com/toddsmith456/Youniversal")
                    inceptionYear.set("2026")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("toddsmith456")
                            name.set("Todd Smith")
                            url.set("https://github.com/toddsmith456")
                        }
                    }

                    scm {
                        connection.set("scm:git:https://github.com/toddsmith456/Youniversal.git")
                        developerConnection.set("scm:git:ssh://github.com/toddsmith456/Youniversal.git")
                        url.set("https://github.com/toddsmith456/Youniversal")
                    }
                }
            }
        }
    }
}
