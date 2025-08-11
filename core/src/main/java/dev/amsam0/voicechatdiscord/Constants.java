package dev.amsam0.voicechatdiscord;

import java.util.List;

public final class Constants {
    public static final String VERSION = "$version";
    public static final String PLUGIN_ID = "voicechat-discord";
    public static final String RELOAD_CONFIG_PERMISSION = "voicechat-discord.reload-config";
    public static final String VOICECHAT_MIN_VERSION = "$voicechatApiVersion";

    public static final List<String> CONFIG_HEADER = List.of(
        "# The Discord category ID where voice channels will be created.",
        "category_id: DISCORD_CATEGORY_ID_HERE",
        "",
        "# The list of Discord bot tokens to use for bridging.",
        "# Each token should be on its own line, starting with a dash.",
        "bot_tokens:",
        "  - MyFirstBotsToken",
        "  - MySecondBotsToken",
        "",
        "# List of Discord user IDs (as numbers) that are bots and should be ignored for join/leave messages.",
        "# Add each bot user ID on its own line, starting with a dash.",
        "bot_user_ids:",
        "  - 123456789012345678",
        "  # Add more bot user IDs here",
        "",
        "# Debug logging level:",
        "# 0 (or lower): No debug logging",
        "# 1: Some debug logging (helpful, not spammy)",
        "# 2: Most debug logging (can be spammy)",
        "# 3 (or higher): All debug logging (very spammy)",
        "debug_level: 0",
        ""
    );
}
