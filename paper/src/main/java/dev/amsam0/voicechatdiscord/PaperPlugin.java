package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static dev.amsam0.voicechatdiscord.Constants.PLUGIN_ID;
import static dev.amsam0.voicechatdiscord.Core.*;

/**
 * Hot-reloadable with PlugManX ({@code plugman reload voicechat-discord}): commands are
 * plain plugin.yml commands (Paper's COMMANDS lifecycle event never fires for a hot-loaded
 * plugin), {@link VoicechatAddonHotSwap} re-wires the Simple Voice Chat add-on, and
 * {@link LibraryLoader} loads a fresh copy of the native library.
 */
public final class PaperPlugin extends JavaPlugin implements Listener {
    public static final Logger LOGGER = LogManager.getLogger(PLUGIN_ID);
    private static PaperPlugin instance;

    private BukkitVoicechatService voicechatService;
    private VoicechatPlugin voicechatPlugin;

    public static PaperPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        platform = new PaperPlatform();

        voicechatService = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (voicechatService == null) {
            throw new IllegalStateException("Simple Voice Chat's BukkitVoicechatService is missing");
        }

        // Natives + config first, so voicechat events never reach a half-started core
        enable();

        voicechatPlugin = new VoicechatPlugin();
        voicechatService.registerPlugin(voicechatPlugin);
        boolean hotLoad;
        try {
            hotLoad = VoicechatAddonHotSwap.attach(voicechatService, voicechatPlugin);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to attach to Simple Voice Chat", e);
        }

        getServer().getPluginManager().registerEvents(this, this);

        if (hotLoad) {
            LOGGER.info("Simple Voice Chat was already running (hot load); relinking existing groups");
            GroupManager.resyncExistingGroups();
        }
    }

    @Override
    public void onDisable() {
        // Detach first: no voicechat event may reach a core that is shutting down
        if (voicechatService != null && voicechatPlugin != null) {
            try {
                VoicechatAddonHotSwap.detach(voicechatService, voicechatPlugin);
            } catch (Throwable e) {
                LOGGER.error("Failed to detach from Simple Voice Chat", e);
            }
        }
        disable();
        instance = null;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        CommandSource source = new PaperCommandSource(sender);
        switch (command.getName()) {
            case "dvcgroup" -> SubCommands.dvcgroup(source, args);
            case "dvcgroupmsg" -> SubCommands.groupMessage(source, args);
            case "cutaway" -> SubCommands.cutaway(source, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equals("dvcgroup") && args.length == 1) {
            String prefix = args[0].toLowerCase();
            return SubCommands.DVCGROUP_SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        GroupManager.handleMinecraftPlayerLeave(event.getPlayer().getUniqueId());
    }

    private record PaperCommandSource(CommandSender sender) implements CommandSource {
        @Override
        public de.maxhenkel.voicechat.api.ServerPlayer player() {
            return sender instanceof Player player && api != null ? api.fromServerPlayer(player) : null;
        }

        @Override
        public boolean hasPermission(String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        public void sendMessage(Component... message) {
            sender.sendMessage(PaperPlatform.toNative(message));
        }
    }
}
