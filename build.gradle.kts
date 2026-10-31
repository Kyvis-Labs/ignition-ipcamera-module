import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    base
    id("io.ia.sdk.modl") version ("0.5.0")
}

// Bump to tell redeploys apart: the stamp below is per HOUR, so two builds in
// one hour share a version. Never add minutes -- Ignition parses every version
// segment as an int and yyyyMMddHHmm overflows ("Exception parsing module.xml").
// A release sets it from the tag (see .github/workflows/release.yml).
val moduleBaseVersion = (findProperty("releaseVersion") as String?) ?: "0.1.0"
val buildStamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"))

allprojects {
    version = "$moduleBaseVersion.$buildStamp"
    group = "com.kyvislabs.camera"
}

// `-Pplatform=...` builds a variant with MediaMTX inside (see gateway/build.gradle.kts).
val platform = (findProperty("platform") as String?)?.takeIf { it.isNotBlank() }
val platformLabel = mapOf(
    "linux-x64" to "Linux x64", "linux-arm64" to "Linux ARM64",
    "windows-x64" to "Windows x64", "macos-arm64" to "macOS Apple silicon",
)

ignitionModule {
    fileName.set(if (platform == null) "Camera" else "Camera-$platform")
    name.set("Camera")
    id.set("com.kyvislabs.camera")
    moduleVersion.set("${project.version}")
    moduleDescription.set(
        "IP camera live view, recording and playback for Perspective, backed by MediaMTX. "
            + if (platform == null) "Uses an external MediaMTX."
            else "Includes MediaMTX for ${platformLabel[platform] ?: platform}.")
    requiredIgnitionVersion.set("8.3.9")
    license.set("license.html")

    moduleDependencies.put("com.inductiveautomation.perspective", "DG")

    projectScopes.putAll(
        mapOf(
            ":gateway" to "G",
            ":web" to "G",
            ":designer" to "D",
            ":common" to "GD"
        )
    )

    hooks.putAll(
        mapOf(
            "com.kyvislabs.camera.gateway.CameraGatewayHook" to "G",
            "com.kyvislabs.camera.designer.CameraDesignerHook" to "D"
        )
    )
    freeModule.set(true)
    // Signed only when given a key; see scripts/build-all.sh.
    skipModlSigning.set(!hasProperty("ignition.signing.keystoreFile"))
}

// The version stamp changes every hour, and moduleContent is not cleared between
// builds, so a stale jar from the previous hour makes zipModule fail with
// "Library exists in multiple versions".
tasks.named("assembleModlStructure") {
    doFirst { delete(layout.buildDirectory.dir("moduleContent")) }
}
