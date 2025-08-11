package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bspfsystems.yamlconfiguration.configuration.InvalidConfigurationException;
import org.bspfsystems.yamlconfiguration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;


/**
 * Core code between Paper.
 */
public final class Core {
    // Set of Discord user IDs (as Long) that are bots and should be ignored for join/leave
    public static final Set<Long> botUserIds = new HashSet<>();
    public static VoicechatServerApi api; // Initiated by VoicechatPlugin
    public static Platform platform; // Initiated upon startup by platform

    public static ArrayList<DiscordBot> bots = new ArrayList<>();
    public static int debugLevel = 0;

    private static native void initializeNatives();

    private static native void setDebugLevel(int debugLevel);

    private static native void shutdownNatives();

    public static void enable() {
        // This should happen first
        try {
            LibraryLoader.load("voicechat_discord");
            initializeNatives();
        } catch (Throwable e) {
            platform.error("Failed to load natives: " + e);
            throw new RuntimeException(e);
        }

        loadConfig();
    }

    public static void disable() {
        int toShutdown = bots.size();
        platform.info("Shutting down " + toShutdown + " bot" + (toShutdown != 1 ? "s" : ""));

        clearBots();

        platform.info("Successfully shutdown " + toShutdown + " bot" + (toShutdown != 1 ? "s" : ""));

        try {
            shutdownNatives();
            platform.info("Successfully shutdown native runtime");
        } catch (Throwable e) {
            platform.error("Failed to shutdown native runtime", e);
        }
    }

    @SuppressWarnings({"DataFlowIssue", "ResultOfMethodCallIgnored"})
    public static void loadConfig() {

        File configFile = new File(platform.getConfigPath());
        if (!configFile.getParentFile().exists())
            configFile.getParentFile().mkdirs();

        // Write a hand-crafted default config with interspersed comments if it doesn't exist
        if (!configFile.exists()) {
            String defaultConfig = String.join(System.lineSeparator(),
                "# The Discord category ID where voice channels will be created.",
                "category_id: DISCORD_CATEGORY_ID_HERE",
                "",
                "# The list of Discord bot tokens to use for bridging.",
                "# Each token should be on its own line, starting with a dash.",
                "bot_tokens:",
                "  - DISCORD_BOT_TOKEN_HERE",
                "",
                "# List of Discord user IDs (as numbers) that are bots and should be ignored for join/leave messages.",
                "# Add each bot user ID on its own line, starting with a dash.",
                "bot_user_ids:",
                "  - DISCORD_BOT_USER_ID_HERE",
                "",
                "# Debug logging level:",
                "# 0 (or lower): No debug logging",
                "# 1: Some debug logging (helpful, not spammy)",
                "# 2: Most debug logging (can be spammy)",
                "# 3 (or higher): All debug logging (very spammy)",
                "debug_level: 0",
                ""
            );
            try (java.io.FileWriter writer = new java.io.FileWriter(configFile)) {
                writer.write(defaultConfig);
            } catch (IOException e) {
                platform.error("Failed to write default config file: " + e);
                throw new RuntimeException(e);
            }
        }

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException e) {
            platform.debug("IOException when loading config", e);
        } catch (InvalidConfigurationException e) {
            platform.error("Failed to load config file");
            throw new RuntimeException(e);
        }

        bots.clear();

        // Load bot_user_ids from config
        botUserIds.clear();
        Object botUserIdsObj = config.getList("bot_user_ids");
        if (botUserIdsObj instanceof List<?> list) {
            for (Object idObj : list) {
                if (idObj instanceof Number n) {
                    botUserIds.add(n.longValue());
                } else if (idObj instanceof String s) {
                    try {
                        botUserIds.add(Long.parseLong(s));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        Object categoryIdObj = config.get("category_id");
        long categoryId;
        if (categoryIdObj instanceof String) {
            try {
                categoryId = Long.parseLong((String) categoryIdObj);
            } catch (NumberFormatException e) {
                platform.error("category_id must be a valid Discord category ID (number as string)");
                return;
            }
        } else if (categoryIdObj instanceof Number) {
            categoryId = ((Number) categoryIdObj).longValue();
        } else {
            platform.error("category_id must be set in the config");
            return;
        }

        Object botTokensObj = config.getList("bot_tokens");
        if (botTokensObj instanceof List<?> botTokensList) {
            for (Object entry : botTokensList) {
                if (entry instanceof String tokenStr) {
                    bots.add(new DiscordBot(tokenStr, categoryId));
                } else if (entry instanceof LinkedHashMap<?,?> map && map.get("token") instanceof String token) {
                    bots.add(new DiscordBot(token, categoryId));
                } else {
                    platform.error("Invalid bot_tokens entry: " + entry);
                }
            }
        }

        platform.info("Using " + bots.size() + " bot" + (bots.size() != 1 ? "s" : ""));

        try {
            debugLevel = (int) config.get("debug_level");
            if (debugLevel > 0) platform.info("Debug level has been set to " + debugLevel);
            setDebugLevel(debugLevel);
        } catch (ClassCastException e) {
            platform.error("Please make sure the debug_level option is a valid integer");
        }
    }

    public static void clearBots() {
        bots.forEach(discordBot -> {
            discordBot.stop();
            discordBot.free();
        });
        bots.clear();

        GroupManager.groupPlayerMap.clear();
        GroupManager.groupBotMap.clear();
        GroupManager.groupAudioChannels.clear();
    }
}
