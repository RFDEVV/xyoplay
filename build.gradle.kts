import java.net.URI

plugins {
    id("net.fabricmc.fabric-loom") version "1.15-SNAPSHOT"
    java
}

group = "id.rfdevv"
version = "2.12.1-client"

base { archivesName.set("XYOPlay-Client") }


repositories {
    maven { url = uri("https://maven.terraformersmc.com/") }
}

dependencies {
    minecraft("com.mojang:minecraft:26.1.2")
    implementation("net.fabricmc:fabric-loader:0.18.4")
    implementation("net.fabricmc.fabric-api:fabric-api:0.155.3+26.1.2")
    implementation("com.terraformersmc:modmenu:18.0.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

// Mod icon: taken from the official RFDEVV favicon at build time (nothing is drawn locally).
// If src/main/resources/assets/xyoplay/icon.png already exists, that file is used and nothing is downloaded.
val iconUrl = "https://rfdevv.com/uploads/favicons/1788947524100-rfffff.png"
val iconOutDir = layout.buildDirectory.dir("generated/icon")
val downloadIcon by tasks.registering {
    val target = iconOutDir.map { it.file("assets/xyoplay/icon.png").asFile }
    val local = layout.projectDirectory.file("src/main/resources/assets/xyoplay/icon.png").asFile
    outputs.file(target)
    doLast {
        val out = target.get()
        out.parentFile.mkdirs()
        if (local.exists()) return@doLast
        try {
            val conn = URI(iconUrl).toURL().openConnection()
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.getInputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            logger.lifecycle("XYOPlay: icon downloaded (${out.length()} bytes)")
        } catch (e: Exception) {
            out.delete()
            logger.warn("XYOPlay: gagal mengunduh ikon ($iconUrl): ${e.message}. Simpan file PNG-nya sebagai src/main/resources/assets/xyoplay/icon.png lalu build ulang.")
        }
    }
}

sourceSets.named("main") {
    resources.srcDir(iconOutDir)
}

tasks.processResources {
    dependsOn(downloadIcon)
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.named("sourcesJar") {
    dependsOn(downloadIcon)
}
