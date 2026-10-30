import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Date-based version: local builds use the Beijing date, so a newer APK always installs over
// an older one without the user uninstalling first.
val buildDate: String = System.getenv("DATE")
    ?.takeIf { it.matches(Regex("""\d{8}""")) }
    ?: LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE)

android {
    namespace = "com.zpw38.watchroot"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.zpw38.watchroot"
        minSdk = 24
        targetSdk = 36
        versionCode = buildDate.toInt()
        versionName = "v$buildDate"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        jniLibs {
            // conscrypt-android ships .so files that are not 16KB-page aligned; extracting them
            // at install time avoids load failures on 16KB page size devices
            useLegacyPackaging = true
        }
    }
    buildFeatures {
        compose = true
    }
}

// ---------------------------------------------------------------------------
// Native payload
//
// The exploit payload ships as an arm64 executable in jniLibs. There is no NDK
// on this machine: the payload is cross-compiled with zig
//
//   python-zig.exe c++ -target aarch64-linux-musl -O2 -static -pthread \
//       -o libghostlock.so <sources>
//
// and dropped into `payload/arm64-v8a/`. The copy task below is the only writer
// of jniLibs, so Gradle's output validation stays happy.
// ---------------------------------------------------------------------------

val payloadDir = rootProject.layout.projectDirectory.dir("payload/arm64-v8a")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")

val copyNativePayload = tasks.register<Copy>("copyNativePayload") {
    group = "build"
    description = "Copies the arm64 exploit payload into jniLibs"
    from(payloadDir) {
        include("*.so")
    }
    into(jniLibsDir)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(copyNativePayload)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.annotation)

    // Material 3 — the whole UI is Material You.
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Wireless debugging pairing (SPAKE2 / HKDF / AES-GCM)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.conscrypt.android)

    // Optional shell backend: borrow a running Shizuku service (no socket, no pairing)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
}
