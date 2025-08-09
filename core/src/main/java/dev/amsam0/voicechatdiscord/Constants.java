package dev.amsam0.voicechatdiscord;

import java.util.List;

public final class Constants {
    public static final String VERSION = "$version";
    public static final String PLUGIN_ID = "voicechat-discord";
    public static final String RELOAD_CONFIG_PERMISSION = "voicechat-discord.reload-config";
    public static final String VOICECHAT_MIN_VERSION = "$voicechatApiVersion";

    public static final List<String> CONFIG_HEADER = List.of(
            "bots:",
            "- token: MyFirstBotsToken",
            "  vc_id: VOICE_CHANNEL_ID_HERE",
            "- token: MySecondBotsToken",
            "",
            "It will enable debug logging according to the level:",
            "- 0 (or lower): No debug logging",
            "- 1: Some debug logging (mainly logging that won't spam the console but can be helpful)",
            "- 2: Most debug logging (will spam the console but excludes logging that is extremely verbose and usually not helpful)",
            "- 3 (or higher): All debug logging (will spam the console)",
            ""
    );
}
