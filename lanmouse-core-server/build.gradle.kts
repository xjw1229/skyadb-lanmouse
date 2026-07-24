plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.server.skyadb.core"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val coreClassesJar = tasks.register<Jar>("bundleCoreClasses") {
    dependsOn("compileReleaseJavaWithJavac")
    archiveFileName.set("skyadb-lanmouse-core-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/lanmouse-core"))
    from(layout.buildDirectory.dir("intermediates/javac/release/compileReleaseJavaWithJavac/classes"))
}

val dexDirectory = layout.buildDirectory.dir("intermediates/lanmouse-core/dex")
val dexCoreServer = tasks.register<Exec>("dexCoreServer") {
    dependsOn(coreClassesJar)
    inputs.file(coreClassesJar.flatMap { it.archiveFile })
    outputs.dir(dexDirectory)

    doFirst {
        delete(dexDirectory)
        dexDirectory.get().asFile.mkdirs()
    }

    commandLine(
        "cmd",
        "/c",
        android.sdkDirectory.resolve("build-tools/36.0.0/d8.bat").absolutePath,
        "--min-api",
        "21",
        "--lib",
        android.sdkDirectory.resolve("platforms/android-36/android.jar").absolutePath,
        "--output",
        dexDirectory.get().asFile.absolutePath,
        coreClassesJar.get().archiveFile.get().asFile.absolutePath,
    )
}

tasks.register<Zip>("packageCoreServer") {
    dependsOn(dexCoreServer)
    from(dexDirectory) {
        include("classes.dex")
    }
    archiveFileName.set("skyadb-lanmouse-core.jar")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/lanmouse-core"))
}
