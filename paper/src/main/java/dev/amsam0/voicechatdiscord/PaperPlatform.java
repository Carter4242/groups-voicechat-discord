package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;

import com.mojang.brigadier.context.CommandContext;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.Sound;

import static dev.amsam0.voicechatdiscord.PaperPlugin.LOGGER;
import static dev.amsam0.voicechatdiscord.Core.api;
import static dev.amsam0.voicechatdiscord.PaperPlugin.commandHelper;

public class PaperPlatform implements Platform {
    @Override
    public void sendActionBar(de.maxhenkel.voicechat.api.Player player, Component... message) {
        var bukkitPlayer = (org.bukkit.entity.Player) player.getPlayer();
        if (bukkitPlayer != null && message != null && message.length > 0) {
            bukkitPlayer.sendActionBar(toNative(message));
        }
    }

    @Override
    public ServerPlayer commandContextToPlayer(CommandContext<?> context) {
        return api.fromServerPlayer(commandHelper.bukkitEntity(context));
    }

    @Override
    public boolean isOperator(CommandContext<?> sender) {
        return commandHelper.bukkitSender(sender).isOp();
    }

    @Override
    public boolean hasPermission(CommandContext<?> sender, String permission) {
        return commandHelper.bukkitSender(sender).hasPermission(permission);
    }

    @Override
    public void sendMessage(CommandContext<?> sender, Component... message) {
        if (commandHelper.bukkitEntity(sender) instanceof Player player) {
            player.sendMessage(toNative(message));
        } else {
            commandHelper.bukkitSender(sender).sendMessage(toNative(message));
        }
    }

    @Override
    public void sendMessage(de.maxhenkel.voicechat.api.Player player, Component... message) {
        ((Player) player.getPlayer()).sendMessage(toNative(message));
    }

    public void sendMessage(CommandSender sender, Component... message) {
        sender.sendMessage(toNative(message));
    }

    private net.kyori.adventure.text.Component toNative(Component... message) {
        java.util.List<net.kyori.adventure.text.Component> components = new java.util.ArrayList<>();

        for (dev.amsam0.voicechatdiscord.Component root : message) {
            dev.amsam0.voicechatdiscord.Component current = root;
            while (current != null) {
                net.kyori.adventure.text.Component mapped = net.kyori.adventure.text.Component.text(
                        current.text(),
                        switch (current.color()) {
                            case WHITE -> NamedTextColor.WHITE;
                            case RED -> NamedTextColor.RED;
                            case YELLOW -> NamedTextColor.YELLOW;
                            case GREEN -> NamedTextColor.GREEN;
                            case GOLD -> NamedTextColor.GOLD;
                            case BLUE -> NamedTextColor.BLUE;
                            case GRAY -> NamedTextColor.GRAY;
                            case AQUA -> NamedTextColor.AQUA;
                            default -> NamedTextColor.WHITE;
                        }
                );
                if (current.clickUrl() != null && !current.clickUrl().isEmpty()) {
                    mapped = mapped.clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(current.clickUrl()));
                }
                if (current.hoverText() != null && !current.hoverText().isEmpty()) {
                    mapped = mapped.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        net.kyori.adventure.text.Component.text(current.hoverText())
                    ));
                }
                components.add(mapped);
                current = current.next();
            }
        }

        if (components.isEmpty()) {
            return net.kyori.adventure.text.Component.empty();
        }
        // Join all components into one flat component
        net.kyori.adventure.text.Component result = net.kyori.adventure.text.Component.empty();
        for (net.kyori.adventure.text.Component c : components) {
            result = result.append(c);
        }
        return result;
    }

    @Override
    public String getName(de.maxhenkel.voicechat.api.Player player) {
        return ((Player) player.getPlayer()).getName();
    }

    @Override
    public String getConfigPath() {
        return "plugins/voicechat-discord/config.yml";
    }

    @Override
    public Loader getLoader() {
        return Loader.PAPER;
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
        var bukkitPlayer = (Player) player.getPlayer();
        if (bukkitPlayer != null) {
            var targetWorld = PaperPlugin.get().getServer().getWorld(position.getWorldName());
            if (targetWorld == null) {
                LOGGER.warn("World " + position.getWorldName() + " not found, cannot teleport player");
                return;
            }

            var targetLoc = new Location(
                targetWorld,
                position.getX(),
                position.getY(),
                position.getZ(),
                position.getYaw(),
                position.getPitch()
            );

            PaperPlugin.get().getServer().getScheduler().runTask(PaperPlugin.get(), () -> {
                bukkitPlayer.teleport(targetLoc);
            });
        }
    }

    @Override
    public PlayerPosition getPlayerPosition(de.maxhenkel.voicechat.api.Player player) {
        var bukkitPlayer = (Player) player.getPlayer();
        if (bukkitPlayer != null) {
            var loc = bukkitPlayer.getLocation();
            return new PlayerPosition(
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch(),
                loc.getWorld().getName()
            );
        }
        return null;
    }

    @Override
    public void playPopSound(de.maxhenkel.voicechat.api.Player player) {
        var bukkitPlayer = (Player) player.getPlayer();
        if (bukkitPlayer != null) {
            PaperPlugin.get().getServer().getScheduler().runTask(PaperPlugin.get(), () -> {
                bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.UI_HUD_BUBBLE_POP, 1.0f, 1.0f);
            });
        }
    }
}
