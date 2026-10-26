// The Perspective components are TypeScript + React, bundled by esbuild (see
// web/build.mjs) into build/generated-resources/mounted/, which becomes a jar on
// the gateway classpath served at /res/camera/*. Uses the node/npm on PATH.
plugins {
    java
}

val generated = layout.buildDirectory.dir("generated-resources")

// On Windows, npm is a .cmd shim that Exec cannot start by its bare name.
val npm = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
    workingDir = projectDir
    commandLine(npm, "ci", "--no-audit", "--no-fund")
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

val bundle by tasks.registering(Exec::class) {
    group = "Ignition Module"
    description = "Type-checks and bundles the Perspective components."
    dependsOn(npmInstall)
    workingDir = projectDir
    commandLine(npm, "run", "build", "--", generated.get().dir("mounted").asFile.absolutePath)
    inputs.dir("src")
    inputs.files("build.mjs", "tsconfig.json", "package-lock.json")
    outputs.dir(generated)
}

sourceSets {
    main {
        output.dir(generated, "builtBy" to listOf(bundle))
    }
}
