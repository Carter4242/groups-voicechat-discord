package dev.amsam0.voicechatdiscord;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static dev.amsam0.voicechatdiscord.PaperPlugin.LOGGER;

public class PaperPlatform implements Platform {
    static net.kyori.adventure.text.Component toNative(Component... message) {
        net.kyori.adventure.text.Component result = net.kyori.adventure.text.Component.empty();
        for (Component root : message) {
            for (Component current = root; current != null; current = current.next()) {
                net.kyori.adventure.text.Component mapped = net.kyori.adventure.text.Component.text(current.text(), switch (current.color()) {
                    case WHITE -> NamedTextColor.WHITE;
                    case RED -> NamedTextColor.RED;
                    case YELLOW -> NamedTextColor.YELLOW;
                    case GREEN -> NamedTextColor.GREEN;
                    case GOLD -> NamedTextColor.GOLD;
                    case BLUE -> NamedTextColor.BLUE;
                    case GRAY -> NamedTextColor.GRAY;
                    case AQUA -> NamedTextColor.AQUA;
                });
                if (current.clickUrl() != null && !current.clickUrl().isEmpty()) {
                    mapped = mapped.clickEvent(ClickEvent.openUrl(current.clickUrl()));
                }
                if (current.hoverText() != null && !current.hoverText().isEmpty()) {
                    mapped = mapped.hoverEvent(HoverEvent.showText(net.kyori.adventure.text.Component.text(current.hoverText())));
                }
                result = result.append(mapped);
            }
        }
        return result;
    }

    private static Player bukkit(de.maxhenkel.voicechat.api.Player player) {
        return (Player) player.getPlayer();
    }

    /** Runs on the main thread; skipped if the plugin was unloaded in the meantime. */
    private static void runSync(Runnable task) {
        PaperPlugin plugin = PaperPlugin.get();
        if (plugin != null && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void sendMessage(de.maxhenkel.voicechat.api.Player player, Component... message) {
        bukkit(player).sendMessage(toNative(message));
    }

    @Override
    public void sendActionBar(de.maxhenkel.voicechat.api.Player player, Component... message) {
        Player bukkitPlayer = bukkit(player);
        if (bukkitPlayer != null && message != null && message.length > 0) {
            bukkitPlayer.sendActionBar(toNative(message));
        }
    }

    @Override
    public String getName(de.maxhenkel.voicechat.api.Player player) {
        return bukkit(player).getName();
    }

    @Override
    public String getConfigPath() {
        return new File(PaperPlugin.get().getDataFolder(), "config.yml").getPath();
    }

    @Override
    public void info(String message) {
        LOGGER.info(message);
    }

    @Override
    public void warn(String message) {
        LOGGER.warn(message);
    }

    @Override
    public void error(String message) {
        LOGGER.error(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        LOGGER.error(message, throwable);
    }

    @Override
    public void teleportPlayer(de.maxhenkel.voicechat.api.Player player, PlayerPosition position) {
        Player bukkitPlayer = bukkit(player);
        if (bukkitPlayer == null) return;
        runSync(() -> {
            World world = Bukkit.getWorld(position.getWorldName());
            if (world == null) {
                LOGGER.warn("World " + position.getWorldName() + " not found, cannot teleport player");
                return;
            }
            bukkitPlayer.teleport(new Location(world, position.getX(), position.getY(), position.getZ(), position.getYaw(), position.getPitch()));
        });
    }

    @Override
    public PlayerPosition getPlayerPosition(de.maxhenkel.voicechat.api.Player player) {
        Player bukkitPlayer = bukkit(player);
        if (bukkitPlayer == null) return null;
        Location loc = bukkitPlayer.getLocation();
        return new PlayerPosition(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), loc.getWorld().getName());
    }

    @Override
    public Collection<UUID> getOnlinePlayerUuids() {
        List<UUID> uuids = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            uuids.add(player.getUniqueId());
        }
        return uuids;
    }

    @Override
    public void playPopSound(de.maxhenkel.voicechat.api.Player player) {
        Player bukkitPlayer = bukkit(player);
        if (bukkitPlayer == null) return;
        runSync(() -> bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.UI_HUD_BUBBLE_POP, 1.0f, 1.0f));
    }
}
