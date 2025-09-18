package dev.amsam0.voicechatdiscord;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.maxhenkel.voicechat.api.ServerPlayer;
import java.util.UUID;
import java.util.function.Consumer;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static dev.amsam0.voicechatdiscord.Constants.RELOAD_CONFIG_PERMISSION;
import static dev.amsam0.voicechatdiscord.Core.*;

/**
 * Subcommands for /dvcgroup
 */
public final class SubCommands {
    @SuppressWarnings("unchecked")
    public static <S> LiteralArgumentBuilder<S> build(LiteralArgumentBuilder<S> builder) {
        return (LiteralArgumentBuilder<S>) ((LiteralArgumentBuilder<Object>) builder)
            .then(literal("reloadconfig").executes(wrapInTry(SubCommands::reloadConfig)))
            .then(literal("restart").executes(wrapInTry(SubCommands::restartBot)))
            .then(literal("stop").executes(wrapInTry(SubCommands::stopBot)))
            .then(literal("message")
                .then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString())
                    .executes(wrapInTry(SubCommands::sendMessageToDiscord))
                )
            );
    }

    /**
     * Standalone builder for /dvcgroupmsg <message>
     */
    public static <S> LiteralArgumentBuilder<S> buildMsg(LiteralArgumentBuilder<S> builder) {
        return builder
            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                .executes(wrapInTry(SubCommands::sendMessageToDiscord))
            );
    }

    /**
     * Sends a message to the Discord channel for the group the sender is in.
     */
    private static void sendMessageToDiscord(CommandContext<?> sender) {
        ServerPlayer player = platform.commandContextToPlayer(sender);
        if (player == null) {
            platform.sendMessage(sender, Component.red("Could not determine your player. Are you running this from console?"));
            return;
        }

        // Find the groupId the player is in
        UUID groupId = null;
        for (var entry : GroupManager.groupPlayerMap.entrySet()) {
            for (var p : entry.getValue()) {
                if (p.getUuid().equals(player.getUuid())) {
                    groupId = entry.getKey();
                    break;
                }
            }
            if (groupId != null) break;
        }
        if (groupId == null) {
            platform.sendMessage(sender, Component.red("You are not in a voicechat group linked to Discord VC."));
            return;
        }

        DiscordBot bot = GroupManager.groupBotMap.get(groupId);
        if (bot == null) {
            platform.sendMessage(sender, Component.red("No Discord bot is assigned to your group."));
            return;
        }

        String text = StringArgumentType.getString(sender, "message");
        if (text == null || text.isEmpty()) {
            platform.sendMessage(sender, Component.red("Message cannot be empty."));
            return;
        }

        String discordMessage = "**" + platform.getName(player) + ":** " + text;
        bot.sendDiscordTextMessageAsync(discordMessage);
        // Broadcast to all group members in Minecraft with [Group] prefix in green and sender's name
        var groupPlayers = GroupManager.groupPlayerMap.get(groupId);
        if (groupPlayers != null && !groupPlayers.isEmpty()) {
            Component prefix = Component.green("[Group] ");
            Component name = Component.gray(platform.getName(player));
            Component msg = Component.white(": " + text);
            for (ServerPlayer p : groupPlayers) {
                platform.sendMessage(p, prefix, name, msg);
            }
        }
    }

    /**
     * Stops the Discord bot for the group the sender is currently in, deleting the Discord voice channel.
     */
    private static void stopBot(CommandContext<?> sender) {
        ServerPlayer player = platform.commandContextToPlayer(sender);
        if (player == null) {
            platform.sendMessage(sender, Component.red("Could not determine your player. Are you running this from console?"));
            return;
        }

        // Find the groupId the player is in
        UUID groupId = null;
        for (var entry : GroupManager.groupPlayerMap.entrySet()) {
            for (var p : entry.getValue()) {
                if (p.getUuid().equals(player.getUuid())) {
                    groupId = entry.getKey();
                    break;
                }
            }
            if (groupId != null) break;
        }
        if (groupId == null) {
            platform.sendMessage(sender, Component.red("You are not in a voicechat group linked to Discord VC."));
            return;
        }

        // Check if sender is op or group owner
        UUID owner = GroupManager.groupOwnerMap.get(groupId);
        if (!platform.isOperator(sender) && (owner == null || !owner.equals(player.getUuid()))) {
            platform.sendMessage(sender, Component.red("You must be the group owner to use this command!"));
            return;
        }

        DiscordBot bot = GroupManager.groupBotMap.get(groupId);
        if (bot == null) {
            platform.sendMessage(sender, Component.red("No Discord bot is assigned to your group."));
            return;
        }

        platform.sendMessage(sender, Component.yellow("Stopping Discord bot for your group..."));
        // Notify all group members
        var groupPlayers = GroupManager.groupPlayerMap.get(groupId);
        if (groupPlayers != null) {
            Component prefix = Component.blue("[Discord] ");
            Component action = Component.red("The Discord bot for your group is being stopped.");
            for (ServerPlayer p : groupPlayers) {
                platform.sendMessage(p, prefix, action);
            }
        }

        UUID finalGroupId = groupId;
        new Thread(() -> {
            try {
                bot.disconnect();
                bot.stop(); // Default: deletes the channel
                platform.sendMessage(sender, Component.green("Successfully stopped the Discord bot for your group."));
            } catch (Throwable e) {
                platform.error("Failed to stop Discord bot for group: " + finalGroupId, e);
                platform.sendMessage(sender, Component.red("Failed to stop the Discord bot for your group. See console for details."));
            }
        }, "voicechat-discord: StopBot").start();
    }

    // Restarts the Discord bot for the group the sender is currently in, without deleting/recreating the Discord voice channel
    private static void restartBot(CommandContext<?> sender) {
        ServerPlayer player = platform.commandContextToPlayer(sender);
        if (player == null) {
            platform.sendMessage(sender, Component.red("Could not determine your player. Are you running this from console?"));
            return;
        }

        // Find the groupId the player is in
        UUID groupId = null;
        for (var entry : GroupManager.groupPlayerMap.entrySet()) {
            for (var p : entry.getValue()) {
                if (p.getUuid().equals(player.getUuid())) {
                    groupId = entry.getKey();
                    break;
                }
            }
            if (groupId != null) break;
        }
        if (groupId == null) {
            platform.sendMessage(sender, Component.red("You are not in a voicechat group linked to Discord VC."));
            return;
        }

        // Check if sender is op or group owner
        UUID owner = GroupManager.groupOwnerMap.get(groupId);
        if (!platform.isOperator(sender) && (owner == null || !owner.equals(player.getUuid()))) {
            platform.sendMessage(sender, Component.red("You must be the group owner to use this command!"));
            return;
        }

        DiscordBot bot = GroupManager.groupBotMap.get(groupId);
        if (bot == null) {
            platform.sendMessage(sender, Component.red("No Discord bot is assigned to your group."));
            return;
        }

        platform.sendMessage(sender, Component.yellow("Restarting Discord bot for your group..."));

        UUID finalGroupId = groupId;
        new Thread(() -> {
            try {
                bot.disconnect();
                bot.stop(false); // Do not delete the channel when restarting
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {}
                bot.logIn();
                bot.start();
                bot.startDiscordAudioThread(finalGroupId);
                platform.sendMessage(sender, Component.green("Successfully restarted the Discord bot for your group."));
            } catch (Throwable e) {
                platform.error("Failed to restart Discord bot for group: " + finalGroupId, e);
                platform.sendMessage(sender, Component.red("Failed to restart the Discord bot for your group. See console for details."));
            }
        }, "voicechat-discord: RestartBot").start();
    }

    private static <S> Command<S> wrapInTry(Consumer<CommandContext<?>> function) {
        return (sender) -> {
            try {
                function.accept(sender);
            } catch (Throwable e) {
                platform.error("An error occurred when running a command", e);
                platform.sendMessage(sender, Component.red("An error occurred when running the command. Please check the console or tell your server owner to check the console."));
            }
            return 1;
        };
    }

    private static void reloadConfig(CommandContext<?> sender) {
        if (!platform.isOperator(sender) && !platform.hasPermission(
                sender,
                RELOAD_CONFIG_PERMISSION
        )) {
            platform.sendMessage(
                    sender,
                    Component.red("You must be an operator or have the `" + RELOAD_CONFIG_PERMISSION + "` permission to use this command!")
            );
            return;
        }

        platform.sendMessage(sender, Component.yellow("Stopping bots..."));

        new Thread(() -> {
            clearBots();

            platform.sendMessage(
                    sender,
                    Component.green("Successfully stopped bots! "),
                    Component.yellow("Reloading config...")
            );

            loadConfig();

            platform.sendMessage(
                    sender,
                    Component.green("Successfully reloaded config! Using " + bots.size() + " bot" + (bots.size() != 1 ? "s" : "") + ".")
            );
        }, "voicechat-discord: Reload Config").start();
    }
}
