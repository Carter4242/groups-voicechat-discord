package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bspfsystems.yamlconfiguration.configuration.InvalidConfigurationException;
import org.bspfsystems.yamlconfiguration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static dev.amsam0.voicechatdiscord.Constants.CONFIG_HEADER;

/**
 * Core code between Paper.
 */
public final class Core {
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

    @SuppressWarnings({"DataFlowIssue", "unchecked", "ResultOfMethodCallIgnored"})
    public static void loadConfig() {
        File configFile = new File(platform.getConfigPath());

        if (!configFile.getParentFile().exists())
            configFile.getParentFile().mkdirs();

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException e) {
            platform.debug("IOException when loading config", e);
        } catch (InvalidConfigurationException e) {
            platform.error("Failed to load config file");
            throw new RuntimeException(e);
        }


        config.addDefault("category_id", "DISCORD_CATEGORY_ID_HERE");
        LinkedHashMap<String, String> defaultBot = new LinkedHashMap<>();
        defaultBot.put("token", "DISCORD_BOT_TOKEN_HERE");
        config.addDefault("bots", List.of(defaultBot));

        config.addDefault("debug_level", 0);

        config.getOptions().setCopyDefaults(true);
        config.getOptions().setHeader(CONFIG_HEADER);
        try {
            config.save(configFile);
        } catch (IOException e) {
            platform.error("Failed to save config file: " + e);
            throw new RuntimeException(e);
        }

        bots.clear();

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

        for (LinkedHashMap<String, Object> bot : (List<LinkedHashMap<String, Object>>) config.getList("bots")) {
            if (bot.get("token") == null) {
                platform.error(
                        "Failed to load a bot, missing token property.");
                continue;
            }
            try {
                bots.add(new DiscordBot((String) bot.get("token"), categoryId));
            } catch (ClassCastException e) {
                platform.error("Failed to load a bot. Please make sure that the token property is a string.");
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
        // Also clear all group mappings when bots are cleared
        dev.amsam0.voicechatdiscord.GroupManager.groupPlayerMap.clear();
        dev.amsam0.voicechatdiscord.GroupManager.groupBotMap.clear();
        dev.amsam0.voicechatdiscord.GroupManager.groupAudioChannels.clear();
    }
}
