
val nativeTargets = listOf(
    "x86_64-unknown-linux-gnu",
    "x86_64-pc-windows-gnu"
)

tasks.register<Copy>("copyNativesToResources") {
    group = "voicechat-discord"
    description = "Copy all native binaries from target/release to resources/natives for packaging"
    nativeTargets.forEach { target ->
        val destDir = when {
            target.contains("linux") && target.contains("x86_64") -> "linux-x64"
            target.contains("windows") && target.contains("x86_64") -> "windows-x64"
            else -> target
        }
        from("core/target/$target/release/") {
            include("libvoicechat_discord.so", "libvoicechat_discord.dylib", "voicechat_discord.dll")
        }
        into("core/src/main/resources/natives/$destDir/")
    }
}

tasks.register<GradleBuild>("build") {
    group = "voicechat-discord"
    dependsOn("copyNativesToResources")
    tasks = listOf(
        ":paper:build",
    )
}
