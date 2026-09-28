import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}




val kadbVersion = libs.versions.kadb.get()
val kadbAndroidAar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    kadbAndroidAar("com.flyfishxu:kadb-android:$kadbVersion")
}

val unpackCompatibleKadbAar = tasks.register<Sync>("unpackCompatibleKadbAar") {
    val sourceAar = kadbAndroidAar.elements.map { it.single().asFile }
    inputs.files(kadbAndroidAar)
    from(sourceAar.map { zipTree(it) })
    into(layout.buildDirectory.dir("intermediates/kadb-compatible/unpacked"))
    filesMatching("META-INF/com/android/build/gradle/aar-metadata.properties") {
        filter { line: String ->
            if (line.startsWith("minCompileSdk=")) "minCompileSdk=36" else line
        }
    }
}

val packageCompatibleKadbAar = tasks.register<Zip>("packageCompatibleKadbAar") {
    dependsOn(unpackCompatibleKadbAar)
    from(unpackCompatibleKadbAar.map { it.destinationDir })
    archiveFileName.set("kadb-android-$kadbVersion-compileSdk36.aar")
    destinationDirectory.set(layout.buildDirectory.dir("compatible-aars"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val compatibleKadbAar = files(packageCompatibleKadbAar.flatMap { it.archiveFile })
    .builtBy(packageCompatibleKadbAar)

val generatedLanMouseAssets = layout.buildDirectory.dir("generated/lanmouse-assets")
val prepareLanMouseAssets = tasks.register<Sync>("prepareLanMouseAssets") {
    dependsOn(":lanmouse-core-server:packageCoreServer", ":lanmouse-server:assembleDebug")
    into(generatedLanMouseAssets)
    from(project(":lanmouse-core-server").layout.buildDirectory.file("outputs/lanmouse-core/skyadb-lanmouse-core.jar")) {
        into("lanmouse")
    }
    from(project(":lanmouse-server").layout.buildDirectory.file("outputs/apk/debug/lanmouse-server-debug.apk")) {
        into("lanmouse")
        rename { "skyadb-lanmouse-server.apk" }
    }
}

android {
    namespace = "com.sky22333.skyadb"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    val ciVersionCode = providers.environmentVariable("VERSION_CODE").map { it.toInt() }.orNull
    val ciVersionName = providers.environmentVariable("VERSION_NAME").orNull
    val signingStoreFile = providers.environmentVariable("SIGNING_STORE_FILE").orNull
    val keyAliasValue = providers.environmentVariable("KEY_ALIAS").orNull
    val keyStorePasswordValue = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
    val keyPasswordValue = providers.environmentVariable("KEY_PASSWORD").orNull
    val releaseSigningEnabled = !signingStoreFile.isNullOrBlank() &&
        !keyAliasValue.isNullOrBlank() &&
        !keyStorePasswordValue.isNullOrBlank() &&
        !keyPasswordValue.isNullOrBlank()

    defaultConfig {
        applicationId = "com.fs.skyadb.lanmouse"
        minSdk = 24
        targetSdk = 36
        versionCode = ciVersionCode ?: 1036
        versionName = ciVersionName ?: "1.0.36"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningEnabled) {
                storeFile = file(signingStoreFile!!)
                storeType = "PKCS12"
                keyAlias = keyAliasValue!!
                storePassword = keyStorePasswordValue!!
                keyPassword = keyPasswordValue!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").assets.srcDir(generatedLanMouseAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareLanMouseAssets)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // KADB 2.1.3 fixes Android 10 RSA keys but publishes an unnecessarily strict API 37 AAR marker.
    implementation(compatibleKadbAar)
    implementation("com.github.Flyfish233:spake2-java:1.1.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    implementation("com.squareup.okio:okio-jvm:3.17.0")
    implementation(libs.fastboot.java)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.timber)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
