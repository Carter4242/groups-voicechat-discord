
plugins {
    java
    id("shared-plugins")
}

project.version = Properties.pluginVersion
project.group = Properties.mavenGroup

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(Properties.javaVersion))
}

tasks.processResources {
    dependsOn(":copyNativesToResources")
}

tasks.jar {
    archiveBaseName.set(Properties.archivesBaseName + "-" + project.name)
}

tasks.register<Copy>("processSources") {
    filteringCharset = Charsets.UTF_8.name()

    val properties = mapOf(
        "version" to Properties.pluginVersion,
        "voicechatApiVersion" to Properties.voicechatApiVersion,
    )
    inputs.properties(properties)

    from("src/main/java") {
        include("**/Constants.java")

        expand(properties)
    }
    into("build/filteredSrc")
}

tasks.compileJava {
    options.encoding = Charsets.UTF_8.name()

    options.release.set(Properties.javaVersion)

    options.headerOutputDirectory = layout.buildDirectory.dir("headers")

    val javaSources = sourceSets["main"].allJava.filter {
        it.name != "Constants.java"
    }.asFileTree

    val processSources = tasks.getByName<Copy>("processSources")
    source = javaSources + fileTree(processSources.destinationDir)
    dependsOn(processSources)
}

dependencies {
    compileOnly("de.maxhenkel.voicechat:voicechat-api:${Properties.voicechatApiVersion}")

    compileOnly("org.bspfsystems:yamlconfiguration:${Properties.yamlConfigurationVersion}")
    compileOnly("com.github.zafarkhaja:java-semver:${Properties.javaSemverVersion}")
    compileOnly("com.google.code.gson:gson:${Properties.gsonVersion}")
    compileOnly("com.mojang:brigadier:1.0.18")
    compileOnly("org.jetbrains:annotations:26.0.2")
}

repositories {
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/releases/") }
    maven { url = uri("https://maven.maxhenkel.de/repository/public") }
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://libraries.minecraft.net") }
    mavenLocal()
}