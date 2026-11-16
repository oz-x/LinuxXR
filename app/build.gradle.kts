import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.linuxxr"
    compileSdk = 34
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.linuxxr"
        // Horizon OS (Quest 3) is Android 12L+ based and arm64-only.
        minSdk = 32
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        // proot and its loader ship as lib*.so so Android extracts them to the
        // (executable) native library dir; apps targeting API 29+ cannot exec from data dirs.
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE*,NOTICE*}"
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/prootJniLibs"))
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// PRoot (termux fork) + talloc, built from pinned upstream sources with the NDK.

data class PinnedSource(val name: String, val url: String, val sha256: String, val topDir: String)

val prootSource = PinnedSource(
    "proot",
    "https://github.com/termux/proot/archive/52d65a4dc140ca554d241a1438ddd4f6b00c9813.tar.gz",
    "895867fce973f050e51f09b949f8a995ce62f4a28be119c791ff0fef3b2cf2b0",
    "proot-52d65a4dc140ca554d241a1438ddd4f6b00c9813",
)
val tallocSource = PinnedSource(
    "talloc",
    "https://www.samba.org/ftp/talloc/talloc-2.4.4.tar.gz",
    "55e47994018c13743485544e7206780ffbb3c8495e704a99636503e6e77abf59",
    "talloc-2.4.4",
)

val nativeSrcDir = layout.buildDirectory.dir("native-src")
val nativeBuildDir = layout.buildDirectory.dir("native-build")
val prootJniDir = layout.buildDirectory.dir("generated/prootJniLibs/arm64-v8a")

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

fun runCmd(log: File, vararg cmd: String) {
    // Output goes to a file: the Gradle daemon does not drain an inherited stdout, which can block the child.
    log.parentFile.mkdirs()
    val code = ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(log).start().waitFor()
    check(code == 0) { "Command failed ($code): ${cmd.joinToString(" ")}\n${log.readText().takeLast(4000)}" }
}

val fetchNativeSources by tasks.registering {
    val outDir = nativeSrcDir
    outputs.dir(outDir)
    doLast {
        val root = outDir.get().asFile.apply { mkdirs() }
        for (src in listOf(prootSource, tallocSource)) {
            val archive = File(root, "${src.name}.tar.gz")
            if (!archive.exists() || sha256(archive) != src.sha256) {
                logger.lifecycle("Downloading ${src.url}")
                URI(src.url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            }
            val actual = sha256(archive)
            check(actual == src.sha256) { "SHA-256 mismatch for ${src.url}: $actual" }
            val dest = File(root, src.name)
            dest.deleteRecursively()
            copy {
                from(tarTree(resources.gzip(archive)))
                into(root)
                // Upstream symlink that Windows checkouts cannot create; unused by the build.
                exclude("**/CLAUDE.md")
            }
            File(root, src.topDir).renameTo(dest)
        }
    }
}

val buildProot by tasks.registering {
    dependsOn(fetchNativeSources)
    inputs.dir(rootProject.file("native"))
    inputs.dir(nativeSrcDir)
    outputs.dir(prootJniDir)
    doLast {
        val sdkDir = android.sdkDirectory
        val cmakeBin = File(sdkDir, "cmake").listFiles()?.maxByOrNull { it.name }?.resolve("bin")
            ?: error("Android SDK CMake not installed (sdkmanager \"cmake;3.31.5\")")
        val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val buildDir = nativeBuildDir.get().asFile
        val src = nativeSrcDir.get().asFile
        runCmd(
            buildDir.resolve("configure.log"),
            cmakeBin.resolve("cmake$exe").path,
            "-S", rootProject.file("native").path,
            "-B", buildDir.path,
            "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${cmakeBin.resolve("ninja$exe").path}",
            "-DCMAKE_TOOLCHAIN_FILE=${android.ndkDirectory.resolve("build/cmake/android.toolchain.cmake").path}",
            "-DANDROID_ABI=arm64-v8a",
            "-DANDROID_PLATFORM=android-29",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DPROOT_SRC=${src.resolve("proot/src").path}",
            "-DTALLOC_SRC=${src.resolve("talloc").path}",
        )
        runCmd(buildDir.resolve("build.log"), cmakeBin.resolve("cmake$exe").path, "--build", buildDir.path)
        val out = prootJniDir.get().asFile.apply { mkdirs() }
        buildDir.resolve("proot").copyTo(out.resolve("libproot.so"), overwrite = true)
        buildDir.resolve("loader").copyTo(out.resolve("libproot-loader.so"), overwrite = true)
    }
}

tasks.named("preBuild") { dependsOn(buildProot) }
