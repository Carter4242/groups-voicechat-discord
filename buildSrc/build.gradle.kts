plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    implementation("com.modrinth.minotaur:com.modrinth.minotaur.gradle.plugin:2.+")
    implementation("com.github.johnrengelman.shadow:com.github.johnrengelman.shadow.gradle.plugin:8.1.1")
}
