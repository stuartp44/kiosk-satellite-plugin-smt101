import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val sdk = sourceSets.create("sdk") {
    java.srcDir("sdk/src")
}

sourceSets.named("main") {
    java.srcDir("src/main/java")
    compileClasspath += sdk.output
}

sourceSets.named("test") {
    java.srcDir("src/test/java")
    compileClasspath += sdk.output + sourceSets["main"].output
    runtimeClasspath += sdk.output + sourceSets["main"].output
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(tasks.named(sdk.compileJavaTaskName))
    classpath += sdk.output
}

tasks.named<JavaCompile>("compileTestJava") {
    dependsOn(tasks.named(sdk.compileJavaTaskName))
    classpath += sdk.output
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Zip>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    entryCompression = ZipEntryCompression.DEFLATED
    isIncludeEmptyDirs = false
}

tasks.withType<ProcessResources>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val manifestFile = layout.projectDirectory.file("kiosk-satellite-plugin.json")
val distDir = layout.projectDirectory.dir("dist")
val generatedManifestFile = distDir.file("kiosk-satellite-plugin.json")
val generatedDexDir = layout.buildDirectory.dir("generated/plugin-dex")
val generatedPluginDir = layout.buildDirectory.dir("generated")
val maxPackageBytes = 4L * 1024L * 1024L
val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.-]+)?")

fun versionNumbers(name: String): List<Int> = Regex("\\d+").findAll(name).map { it.value.toInt() }.toList()

fun compareVersionLists(left: List<Int>, right: List<Int>): Int {
    val max = maxOf(left.size, right.size)
    for (index in 0 until max) {
        val leftValue = if (index < left.size) left[index] else -1
        val rightValue = if (index < right.size) right[index] else -1
        if (leftValue != rightValue) {
            return leftValue.compareTo(rightValue)
        }
    }
    return 0
}

fun <T> maxByVersion(items: Iterable<T>, name: (T) -> String): T? = items.maxWithOrNull(Comparator { left, right ->
    compareVersionLists(versionNumbers(name(left)), versionNumbers(name(right)))
})

fun readManifest(): MutableMap<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return JsonSlurper().parseText(manifestFile.asFile.readText(StandardCharsets.UTF_8)) as MutableMap<String, Any?>
}

fun packageVersion(): String {
    val manifest = readManifest()
    val chosen = (findProperty("pluginVersion") as String?) ?: manifest["version"]?.toString().orEmpty()
    require(versionPattern.matches(chosen)) {
        "Plugin version must use major.minor.patch with an optional prerelease suffix."
    }
    return chosen
}

fun pluginId(): String = readManifest()["id"]?.toString() ?: error("Manifest id is required")

fun minAndroidSdk(): Int = when (val value = readManifest()["minAndroidSdk"]) {
    is Number -> value.toInt()
    is String -> value.toInt()
    else -> error("Manifest minAndroidSdk is required")
}

fun builtManifestText(): String {
    val manifest = readManifest()
    manifest["version"] = packageVersion()
    return JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n"
}

fun androidSdkRoot(): File {
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val root = if (!env.isNullOrBlank()) File(env) else File(System.getProperty("user.home"), "android-sdk")
    require(root.isDirectory) { "Set ANDROID_HOME or ANDROID_SDK_ROOT to an Android SDK with platforms and build-tools installed." }
    return root
}

fun androidJar(): File {
    val override = findProperty("androidPlatform")?.toString()
    val platformsDir = androidSdkRoot().resolve("platforms")
    val candidates = platformsDir.listFiles()?.filter { it.isDirectory && it.resolve("android.jar").isFile } ?: emptyList()
    val selected = if (!override.isNullOrBlank()) {
        candidates.firstOrNull { it.name == "android-$override" }
            ?: error("Android platform android-$override was not found under ${platformsDir.absolutePath}.")
    } else {
        maxByVersion(candidates) { it.name } ?: error("No Android platforms with android.jar were found under ${platformsDir.absolutePath}.")
    }
    return selected.resolve("android.jar")
}

fun d8Binary(): File {
    val buildToolsDir = androidSdkRoot().resolve("build-tools")
    val candidates = buildToolsDir.listFiles()?.filter { it.isDirectory && it.resolve("d8").isFile } ?: emptyList()
    val selected = maxByVersion(candidates) { it.name }
        ?: error("No Android build-tools with d8 were found under ${buildToolsDir.absolutePath}.")
    return selected.resolve("d8")
}

val generatePackageManifest = tasks.register("generatePackageManifest") {
    inputs.file(manifestFile)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("pluginVersion", providers.provider { packageVersion() })
    outputs.file(generatedManifestFile)
    doLast {
        val output = generatedManifestFile.asFile
        output.parentFile.mkdirs()
        output.writeText(builtManifestText(), StandardCharsets.UTF_8)
    }
}

val dexPlugin = tasks.register<Exec>("dexPlugin") {
    val mainJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    dependsOn(tasks.named("jar"))
    inputs.file(mainJar)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(configurations.runtimeClasspath)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(manifestFile)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedDexDir)
    doFirst {
        val outputDir = generatedDexDir.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val runtimeFiles = configurations.runtimeClasspath.get().files.sortedBy { it.absolutePath }
        val programJar = mainJar.get().asFile
        require(programJar.isFile) { "Compiled plugin jar was not found." }
        commandLine(
            buildList {
                add(d8Binary().absolutePath)
                add("--min-api")
                add(minAndroidSdk().toString())
                add("--lib")
                add(androidJar().absolutePath)
                add("--output")
                add(outputDir.absolutePath)
                add(programJar.absolutePath)
                runtimeFiles.forEach { add(it.absolutePath) }
            }
        )
    }
}

val buildPluginJar = tasks.register<Zip>("buildPluginJar") {
    dependsOn(dexPlugin)
    destinationDirectory.set(generatedPluginDir)
    archiveFileName.set("plugin.jar")
    from(generatedDexDir) {
        include("classes*.dex")
    }
}

val packagePlugin = tasks.register<Zip>("packagePlugin") {
    dependsOn(generatePackageManifest, buildPluginJar)
    destinationDirectory.set(distDir)
    archiveFileName.set(providers.provider { "${pluginId()}-${packageVersion()}.zip" })
    from(generatedManifestFile) {
        rename { "kiosk-satellite-plugin.json" }
    }
    from(buildPluginJar.flatMap { it.archiveFile }) {
        rename { "plugin.jar" }
    }
    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.dir("assets")) {
        into("assets")
    }
    doLast {
        val zipFile = archiveFile.get().asFile
        require(zipFile.length() <= maxPackageBytes) {
            "Plugin ZIP must not exceed 4 MiB."
        }
        val expandedBytes = zipTree(zipFile).files.sumOf { it.length() }
        require(expandedBytes <= maxPackageBytes) {
            "Expanded plugin contents must not exceed 4 MiB."
        }
    }
}

val writeChecksum = tasks.register("writeChecksum") {
    dependsOn(packagePlugin)
    val zipFile = packagePlugin.flatMap { it.archiveFile }
    val checksumFile = zipFile.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") }
    inputs.file(zipFile)
    outputs.file(checksumFile)
    doLast {
        val artifact = zipFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
        val hash = digest.joinToString(separator = "") { "%02x".format(it) }
        checksumFile.get().writeText("$hash  ${artifact.name}\n", StandardCharsets.UTF_8)
    }
}

tasks.named("assemble") {
    dependsOn(writeChecksum)
}

tasks.named("clean") {
    doLast {
        delete(distDir)
    }
}
