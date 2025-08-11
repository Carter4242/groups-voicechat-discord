package dev.amsam0.voicechatdiscord;

import com.mojang.brigadier.context.CommandContext;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static dev.amsam0.voicechatdiscord.PaperPlugin.LOGGER;
import static dev.amsam0.voicechatdiscord.PaperPlugin.commandHelper;

public class PaperPlatform implements Platform {
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
        net.kyori.adventure.text.Component nativeComponent = null;

        for (var component : message) {
            net.kyori.adventure.text.Component mapped = net.kyori.adventure.text.Component.text(
                    component.text(),
                    switch (component.color()) {
                        case WHITE -> NamedTextColor.WHITE;
                        case RED -> NamedTextColor.RED;
                        case YELLOW -> NamedTextColor.YELLOW;
                        case GREEN -> NamedTextColor.GREEN;
                    }
            );
            if (nativeComponent == null) {
                nativeComponent = mapped;
            } else {
                nativeComponent = nativeComponent.append(mapped);
            }
        }

        if (nativeComponent == null) {
            return net.kyori.adventure.text.Component.empty();
        }
        return nativeComponent;
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
}
