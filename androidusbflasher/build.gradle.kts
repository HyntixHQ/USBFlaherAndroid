plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hyntix.lib.androidusbflasher"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 33

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("net.java.dev.jna:jna:5.16.0@aar")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Build Rust library for the library module
tasks.register<Exec>("cargoBuild") {
    val workspaceRoot = project.projectDir.resolve("rust-lib")
    val jniLibsDir = project.file("src/main/jniLibs/arm64-v8a")

    val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/home/raja/Android/Sdk"
    val ndkPath = "$sdkDir/ndk/30.0.14904198"
    val linkerPath = "$ndkPath/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"

    doFirst {
        jniLibsDir.mkdirs()
    }

    workingDir(workspaceRoot)
    environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linkerPath)
    commandLine("cargo", "build", "--release", "--target", "aarch64-linux-android")

    doLast {
        val builtLib = workspaceRoot.resolve("target/aarch64-linux-android/release/libhyntix_usb_flasher_jni.so")
        if (!builtLib.exists()) {
             throw GradleException("Rust build failed: library not found at $builtLib")
        }
        builtLib.copyTo(jniLibsDir.resolve("libhyntix_usb_flasher_jni.so"), overwrite = true)
        println("Rust library copied to $jniLibsDir")
    }
}

tasks.register<Exec>("generateBindings") {
    dependsOn("cargoBuild")
    val workspaceRoot = project.projectDir.resolve("rust-lib")
    val builtLib = workspaceRoot.resolve("target/aarch64-linux-android/release/libhyntix_usb_flasher_jni.so")
    val outDir = project.layout.buildDirectory.dir("generated/uniffi").get().asFile

    doFirst {
        outDir.mkdirs()
    }

    workingDir(workspaceRoot)
    val userHome = System.getProperty("user.home")
    commandLine("$userHome/.cargo/bin/uniffi-bindgen", "generate", builtLib.absolutePath, "--language", "kotlin", "--out-dir", outDir.absolutePath, "--no-format")

    doLast {
        val generatedFile = outDir.resolve("uniffi/hyntix_usb_flasher_jni/hyntix_usb_flasher_jni.kt")
        if (generatedFile.exists()) {
             val destFile = project.file("src/main/java/com/hyntix/lib/androidusbflasher/UsbFlasherNative.kt")

             var content = generatedFile.readText()
             // Ensure it has our package name
             val packageLine = "package com.hyntix.lib.androidusbflasher"
             content = if (content.contains("package ")) {
                 content.replace(Regex("package .*"), packageLine)
             } else {
                 "$packageLine\n\n$content"
             }

             destFile.writeText(content)
             println("Generated bindings copied to $destFile")
        } else {
             println("Warning: Generated Kotlin file not found in $outDir/uniffi/hyntix_usb_flasher_jni/")
             // Try to find it if it moved
             project.fileTree(outDir).filter { it.name == "hyntix_usb_flasher_jni.kt" }.forEach {
                 println("Found at alternative path: ${it.absolutePath}")
             }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("generateBindings")
}
