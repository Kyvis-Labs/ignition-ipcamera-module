import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    base
    id("io.ia.sdk.modl") version ("0.5.0")
}

// Bump to tell redeploys apart: the stamp below is per HOUR, so two builds in
// one hour share a version. Never add minutes -- Ignition parses every version
// segment as an int and yyyyMMddHHmm overflows ("Exception parsing module.xml").
val moduleBaseVersion = "0.1.0"
val buildStamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"))

allprojects {
    version = "$moduleBaseVersion.$buildStamp"
    group = "com.kyvislabs.camera"
}

ignitionModule {
    fileName.set("Camera")
    name.set("Camera")
    id.set("com.kyvislabs.camera")
    moduleVersion.set("${project.version}")
    moduleDescription.set("IP camera live view, recording and playback for Perspective, backed by MediaMTX.")
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
    skipModlSigning.set(true)
}

// The version stamp changes every hour, and moduleContent is not cleared between
// builds, so a stale jar from the previous hour makes zipModule fail with
// "Library exists in multiple versions".
tasks.named("assembleModlStructure") {
    doFirst { delete(layout.buildDirectory.dir("moduleContent")) }
}
