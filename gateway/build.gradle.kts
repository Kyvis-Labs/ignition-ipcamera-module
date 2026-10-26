import java.net.URI
import java.security.MessageDigest

plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    implementation(projects.common)
    modlImplementation(projects.web)

    compileOnly(libs.ignition.common)
    compileOnly(libs.ignition.gateway.api)
    compileOnly(libs.ignition.perspective.gateway)
    compileOnly(libs.ignition.perspective.common)

    testImplementation(libs.ignition.common)
    testImplementation(libs.ignition.gateway.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// ---- embedded MediaMTX ---------------------------------------------------------------------
// `-Pplatform=<linux-x64|linux-arm64|windows-x64|macos-arm64>` bundles that platform's MediaMTX
// binary into the gateway jar (com/kyvislabs/camera/mediamtx/); without it the module expects
// an external MediaMTX. Releases are pinned by version and SHA-256, never "latest": the module
// drives MediaMTX through its v3 API and config keys, which change between releases.
val mediamtxVersion = "1.21.2"
val mediamtxAssets = mapOf(
    "linux-x64" to ("linux_amd64.tar.gz" to "121be6e00397e473a95d83c75f4fcd3819a6af3b0e1a50889cba43ab4fab6a27"),
    "linux-arm64" to ("linux_arm64.tar.gz" to "180a235374a548d9133cdd5e20f5dbd8b01317be1c86e0d7e66df585dc42ed6f"),
    "windows-x64" to ("windows_amd64.zip" to "a84224f5a490855684e62ba6fb71e80ea2a5e16aed61883bb58380290e93e467"),
    "macos-arm64" to ("darwin_arm64.tar.gz" to "7d0d0c5c11527cbd10bde1c3d728864eb0334a9b653566cd73c0259750cbf093"),
)
val platform = (findProperty("platform") as String?)?.takeIf { it.isNotBlank() }

if (platform != null) {
    val (suffix, sha256) = mediamtxAssets[platform]
        ?: throw GradleException("unknown platform '$platform'; one of ${mediamtxAssets.keys}")
    val asset = "mediamtx_v${mediamtxVersion}_$suffix"
    val archive = rootProject.layout.projectDirectory.file(".gradle/mediamtx/$asset")
    val bundleDir = layout.buildDirectory.dir("mediamtx/$platform")

    val fetchMediamtx by tasks.registering {
        description = "Downloads and verifies the pinned MediaMTX release for $platform."
        outputs.file(archive)
        doLast {
            val f = archive.asFile
            fun digest() = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (!f.exists() || digest() != sha256) {
                f.parentFile.mkdirs()
                val url = "https://github.com/bluenviron/mediamtx/releases/download/v$mediamtxVersion/$asset"
                logger.lifecycle("Downloading $url")
                URI(url).toURL().openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                val got = digest()
                if (got != sha256) {
                    f.delete()
                    throw GradleException("$asset: SHA-256 $got, expected $sha256")
                }
            }
        }
    }

    val bundleMediamtx by tasks.registering(Sync::class) {
        description = "Unpacks MediaMTX for $platform into the gateway jar's resources."
        dependsOn(fetchMediamtx)
        val tree = if (suffix.endsWith(".zip")) zipTree(archive) else tarTree(resources.gzip(archive))
        from(tree) {
            include("mediamtx", "mediamtx.exe", "LICENSE")
            rename("LICENSE", "LICENSE-mediamtx")
        }
        into(bundleDir.map { it.dir("com/kyvislabs/camera/mediamtx") })
        val binary = if (suffix.endsWith(".zip")) "mediamtx.exe" else "mediamtx"
        doLast {
            destinationDir.resolve("mediamtx.properties").writeText(
                "platform=$platform\nversion=$mediamtxVersion\nbinary=$binary\nsha256=$sha256\n")
        }
    }

    sourceSets.main { resources.srcDir(bundleMediamtx.map { bundleDir.get() }) }
}
