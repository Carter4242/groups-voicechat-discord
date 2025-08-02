package dev.amsam0.voicechatdiscord;

import java.util.List;

public final class Constants {
    public static final String VERSION = "$version";
    public static final String PLUGIN_ID = "voicechat-discord";
    public static final String RELOAD_CONFIG_PERMISSION = "voicechat-discord.reload-config";
    public static final String VOICECHAT_MIN_VERSION = "$voicechatApiVersion";

    public static final List<String> CONFIG_HEADER = List.of(
            "To add a bot, just copy paste the following into bots:",
            "",
            "bots:",
            "- token: DISCORD_BOT_TOKEN_HERE",
            "  vc_id: VOICE_CHANNEL_ID_HERE",
            "",
            "Example for 2 bots:",
            "",
            "bots:",
            "- token: MyFirstBotsToken",
            "  vc_id: 1234567890123456789",
            "- token: MySecondBotsToken",
            "  vc_id: 9876543210987654321",
            "",
            "If you are only using 1 bot, just replace DISCORD_BOT_TOKEN_HERE with your bot's token and replace VOICE_CHANNEL_ID_HERE with the voice channel ID.",
            "",
            "If you are reporting an issue or trying to figure out what's causing an issue, you may find the `debug_level` option helpful.",
            "It will enable debug logging according to the level:",
            "- 0 (or lower): No debug logging",
            "- 1: Some debug logging (mainly logging that won't spam the console but can be helpful)",
            "- 2: Most debug logging (will spam the console but excludes logging that is extremely verbose and usually not helpful)",
            "- 3 (or higher): All debug logging (will spam the console)",
            "",
            "For more information on getting everything setup: https://github.com/amsam0/voicechat-discord#readme"
    );
}
