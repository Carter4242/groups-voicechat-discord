plugins {
    java
    id("shared-plugins")
}

val archivesBaseName = "${Properties.archivesBaseName}-${project.name}"

project.version = Properties.pluginVersion
project.group = Properties.mavenGroup

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(Properties.javaVersion))
}

tasks.compileJava {
    options.encoding = Charsets.UTF_8.name()

    options.release.set(Properties.javaVersion)
}

tasks.processResources {
    filteringCharset = Charsets.UTF_8.name()

    val properties = mapOf(
        "version" to Properties.pluginVersion,
        "paperApiVersion" to Properties.paperApiVersion,
    )
    inputs.properties(properties)

    filesMatching("plugin.yml") {
        expand(properties)
    }
}

// The shaded jar is the plugin. It only uses the Bukkit/Paper API (no NMS), so it is
// marked mojang-mapped: Paper then loads it as-is instead of remapping it, which stalls
// the main thread for seconds on every hot load (plugman) of a new build.
tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    relocate("org.bspfsystems.yamlconfiguration", "dev.amsam0.voicechatdiscord.shadow.yamlconfiguration")
    relocate("org.yaml.snakeyaml", "dev.amsam0.voicechatdiscord.shadow.snakeyaml")

    archiveBaseName.set(archivesBaseName)
    archiveClassifier.set("")
    archiveVersion.set(Properties.pluginVersion)

    manifest {
        attributes("paperweight-mappings-namespace" to "mojang")
    }

    from(file("${rootDir}/LICENSE")) {
        rename { "${it}_${Properties.archivesBaseName}" }
    }
}

tasks.jar {
    archiveBaseName.set(archivesBaseName)
    archiveClassifier.set("")
    archiveVersion.set("${Properties.pluginVersion}-raw")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${Properties.paperApiDependencyVersion}")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:${Properties.voicechatApiVersion}")

    shadow("org.bspfsystems:yamlconfiguration:${Properties.yamlConfigurationVersion}")
    shadow(project(":core"))
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://maven.maxhenkel.de/repository/public") }
    mavenLocal()
}
