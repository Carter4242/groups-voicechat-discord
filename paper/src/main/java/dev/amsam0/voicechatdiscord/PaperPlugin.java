package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import dev.amsam0.voicechatdiscord.post_1_20_6.Post_1_20_6_CommandHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import static dev.amsam0.voicechatdiscord.Constants.PLUGIN_ID;
import static dev.amsam0.voicechatdiscord.Core.*;

public final class PaperPlugin extends JavaPlugin {
    public static final Logger LOGGER = LogManager.getLogger(PLUGIN_ID);
    public static PaperPlugin INSTANCE;
    public static CommandHelper commandHelper;

    private PaperVoicechatPlugin voicechatPlugin;

    public static PaperPlugin get() {
        return INSTANCE;
    }

    @Override
    public void onEnable() {
        INSTANCE = this;

        if (platform == null) {
            platform = new PaperPlatform();
        }

        commandHelper = new Post_1_20_6_CommandHelper();

        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service != null) {
            voicechatPlugin = new PaperVoicechatPlugin();
            service.registerPlugin(voicechatPlugin);
            LOGGER.info("Successfully registered voicechat discord plugin");
        } else {
            LOGGER.error("Failed to register voicechat discord plugin");
            throw new RuntimeException("Failed to register voicechat discord plugin");
        }

        enable();

        commandHelper.registerCommands();
    }

    @Override
    public void onDisable() {
        disable();

        if (voicechatPlugin != null) {
            getServer().getServicesManager().unregister(voicechatPlugin);
            LOGGER.info("Successfully unregistered voicechat discord plugin");
        }
    }
}
