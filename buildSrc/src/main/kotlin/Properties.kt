@Suppress("ConstPropertyName", "MemberVisibilityCanBePrivate")
object Properties {
    const val javaVersion = 21

    /* Project */
    const val pluginVersion = "3.0.11" // Make sure to sync with setup_servers.sh
    const val mavenGroup = "dev.amsam0.voicechatdiscord"
    const val archivesBaseName = "voicechat-discord"
    const val modrinthProjectId = "S1jG5YV5"

    /* Paper */
    const val paperApiVersion = "1.19"
    val paperSupportedMinecraftVersions = listOf(
        "1.21.7",
        "1.21.8"
    )
    val paperDevBundleVersion = "${paperSupportedMinecraftVersions.last()}-R0.1-SNAPSHOT"

    /* Dependencies */
    const val voicechatApiVersion = "2.4.11"
    const val yamlConfigurationVersion = "2.0.2"
    const val javaSemverVersion = "0.10.2"
    const val gsonVersion = "2.10.1"
}
