plugins {
    id("fabric-loom") version "1.8-SNAPSHOT"
    id("maven-publish")
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    mavenCentral()
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
}

val minecraft_version = property("minecraft_version") as String
val yarn_mappings = property("yarn_mappings") as String
val loader_version = property("loader_version") as String
val fabric_version = property("fabric_version") as String

dependencies {
    implementation("org.jetbrains:annotations:15.0")
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${minecraft_version}")
    mappings("net.fabricmc:yarn:${yarn_mappings}:v2")
    modImplementation("net.fabricmc:fabric-loader:${loader_version}")

    // Fabric API
    modImplementation("net.fabricmc.fabric-api:fabric-api:${fabric_version}")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraft_version)
    inputs.property("loader_version", loader_version)
    filteringCharset = "UTF-8"


}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

tasks.named("runClient") {
    dependsOn("clean", "build")
}