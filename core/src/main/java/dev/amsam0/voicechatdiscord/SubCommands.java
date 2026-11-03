package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;

import java.util.List;
import java.util.UUID;

import static dev.amsam0.voicechatdiscord.Constants.CUTAWAY_PERMISSION;
import static dev.amsam0.voicechatdiscord.Constants.RELOAD_CONFIG_PERMISSION;
import static dev.amsam0.voicechatdiscord.Core.*;

/**
 * Platform-independent command handlers:
 * <ul>
 *     <li>{@code /dvcgroup <restart|stop|reloadconfig|message <text>>}</li>
 *     <li>{@code /dvcgroupmsg <text>} (alias {@code /grm}; the server aliases {@code /g} to it)</li>
 *     <li>{@code /cutaway <delay_seconds> <return_seconds>}</li>
 * </ul>
 */
public final class SubCommands {
    public static final List<String> DVCGROUP_SUBCOMMANDS = List.of("restart", "stop", "reloadconfig", "message");

    private SubCommands() {
    }

    /** /dvcgroup with its raw arguments. */
    public static void dvcgroup(CommandSource source, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "restart" -> run(source, () -> restartBot(source));
            case "stop" -> run(source, () -> stopBot(source));
            case "reloadconfig" -> run(source, () -> reloadConfig(source));
            case "message" -> run(source, () -> groupMessage(source, join(args, 1)));
            default -> source.sendMessage(Component.red("Usage: /dvcgroup <restart|stop|reloadconfig|message <text>>"));
        }
    }

    /** /dvcgroupmsg, /grm, /g with the message words as arguments. */
    public static void groupMessage(CommandSource source, String[] args) {
        run(source, () -> groupMessage(source, join(args, 0)));
    }

    public static void cutaway(CommandSource source, String[] args) {
        run(source, () -> cutawayCommand(source, args));
    }

    /**
     * Sends a chat message to everyone in the sender's voicechat group, and to the
     * group's Discord channel when it has one. Works in every group.
     */
    private static void groupMessage(CommandSource source, String text) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return;
        if (text.isBlank()) {
            source.sendMessage(Component.red("Usage: /g <message>"));
            return;
        }
        Group group = GroupManager.getGroupOf(player.getUuid());
        if (group == null) {
            source.sendMessage(Component.red("You are not in a voice chat group."));
            return;
        }

        String name = platform.getName(player);
        Component prefix = Component.green("[Group] ");
        Component sender = Component.gray(name);
        Component message = Component.white(": " + text);
        for (ServerPlayer member : GroupManager.getOnlineMembers(group.getId())) {
            platform.sendMessage(member, prefix, sender, message);
        }

        DiscordBot bot = GroupManager.groupBotMap.get(group.getId());
        if (bot != null && bot.getDiscordChannelId() != null) {
            bot.sendDiscordTextMessageAsync("**" + name + ":** " + text);
        }
    }

    /** Stops the Discord bot of the sender's group (deletes its channel unless it is the permanent one). */
    private static void stopBot(CommandSource source) {
        LinkedGroup linked = requireLinkedGroup(source);
        if (linked == null) return;
        if (!source.hasPermission(RELOAD_CONFIG_PERMISSION) && !linked.isOwnedBy(source)) {
            source.sendMessage(Component.red("You must be the group owner to use this command!"));
            return;
        }

        source.sendMessage(Component.yellow("Stopping Discord bot for your group..."));
        Component prefix = Component.blue("[Discord] ");
        Component action = Component.red("The Discord bot for your group is being stopped.");
        for (ServerPlayer member : GroupManager.getOnlineMembers(linked.groupId)) {
            platform.sendMessage(member, prefix, action);
        }

        DiscordBot bot = linked.bot;
        boolean permanent = GroupManager.isPermanentGroup(linked.groupId);
        new Thread(() -> {
            if (!bot.getLifecycleLock().tryLock()) {
                source.sendMessage(Component.red("Another start/stop/restart of this bot is already in progress. Please wait a moment."));
                return;
            }
            try {
                bot.disconnect();
                if (permanent) {
                    bot.stop(false);
                    GroupManager.updatePermanentChannelNameForShutdown(bot);
                } else {
                    // Unlink first (while the channel ID is still known) so group chat
                    // and audio bridging stop acting on a dead Discord channel.
                    GroupManager.unlinkGroupFromDiscord(linked.groupId);
                    bot.stop(); // deletes the channel
                }
                source.sendMessage(Component.green("Successfully stopped the Discord bot for your group."));
            } catch (Throwable e) {
                platform.error("Failed to stop Discord bot for group: " + linked.groupId, e);
                source.sendMessage(Component.red("Failed to stop the Discord bot for your group. See console for details."));
            } finally {
                bot.getLifecycleLock().unlock();
            }
        }, "voicechat-discord: StopBot").start();
    }

    /** Restarts the Discord bot of the sender's group without deleting its channel. */
    private static void restartBot(CommandSource source) {
        LinkedGroup linked = requireLinkedGroup(source);
        if (linked == null) return;
        // Anyone in the permanent group may restart it; other groups need the owner.
        if (!GroupManager.isPermanentGroup(linked.groupId)
                && !source.hasPermission(RELOAD_CONFIG_PERMISSION) && !linked.isOwnedBy(source)) {
            source.sendMessage(Component.red("You must be the group owner to use this command!"));
            return;
        }

        source.sendMessage(Component.yellow("Restarting Discord bot for your group..."));
        DiscordBot bot = linked.bot;
        new Thread(() -> {
            // tryLock: spamming restart must not stack interleaved stop/start sequences
            // (that leaked duplicate audio threads and stale voice sessions).
            if (!bot.getLifecycleLock().tryLock()) {
                source.sendMessage(Component.red("A restart of this bot is already in progress. Please wait a moment."));
                return;
            }
            try {
                bot.disconnect();
                bot.stop(false);
                try {
                    // Give Discord time to process the disconnect before rejoining,
                    // or the join can be cancelled by the leave's gateway echo.
                    Thread.sleep(750);
                } catch (InterruptedException ignored) {
                }
                if (!bot.logIn()) {
                    source.sendMessage(Component.red("Failed to log the Discord bot back in. See console for details."));
                    return;
                }
                if (!GroupManager.startVoiceWithRetry(bot)) {
                    source.sendMessage(Component.red("The Discord bot could not rejoin the voice channel. See console for details, then try /dvcgroup restart again."));
                    return;
                }
                bot.startDiscordAudioThread(linked.groupId);
                source.sendMessage(Component.green("Successfully restarted the Discord bot for your group."));
            } catch (Throwable e) {
                platform.error("Failed to restart Discord bot for group: " + linked.groupId, e);
                source.sendMessage(Component.red("Failed to restart the Discord bot for your group. See console for details."));
            } finally {
                bot.getLifecycleLock().unlock();
            }
        }, "voicechat-discord: RestartBot").start();
    }

    private static void reloadConfig(CommandSource source) {
        if (!source.hasPermission(RELOAD_CONFIG_PERMISSION)) {
            source.sendMessage(Component.red("You need the `" + RELOAD_CONFIG_PERMISSION + "` permission to use this command!"));
            return;
        }
        source.sendMessage(Component.yellow("Stopping bots..."));
        new Thread(() -> {
            clearBots();
            source.sendMessage(Component.green("Successfully stopped bots! "), Component.yellow("Reloading config..."));
            loadConfig();
            // clearBots() dropped every group<->bot link; relink like a fresh start.
            GroupManager.resyncExistingGroups();
            source.sendMessage(Component.green("Successfully reloaded config! Using " + bots.size() + " bot" + (bots.size() != 1 ? "s" : "") + "."));
        }, "voicechat-discord: Reload Config").start();
    }

    /**
     * Teleports everyone in the sender's group to the sender after delay_seconds,
     * then back to where they were after another return_seconds.
     */
    private static void cutawayCommand(CommandSource source, String[] args) {
        if (!source.hasPermission(CUTAWAY_PERMISSION)) return;
        ServerPlayer player = requirePlayer(source);
        if (player == null) return;

        int delaySeconds = args.length == 2 ? parseInt(args[0], 0, 3600) : -1;
        int returnSeconds = args.length == 2 ? parseInt(args[1], 1, 3600) : -1;
        if (delaySeconds < 0 || returnSeconds < 0) {
            source.sendMessage(Component.red("Usage: /cutaway <delay_seconds 0-3600> <return_seconds 1-3600>"));
            return;
        }
        Group group = GroupManager.getGroupOf(player.getUuid());
        if (group == null) {
            source.sendMessage(Component.red("You must be in a voice chat group to use this command!"));
            return;
        }
        PlayerPosition targetPos = platform.getPlayerPosition(player);
        if (targetPos == null) {
            source.sendMessage(Component.red("Could not get your position!"));
            return;
        }

        UUID groupId = group.getId();
        UUID initiatorUuid = player.getUuid();
        new Thread(() -> {
            try {
                Thread.sleep(delaySeconds * 1000L);
                String sessionId = CutawayManager.startCutaway(groupId, targetPos, initiatorUuid);
                Thread.sleep(returnSeconds * 1000L);
                if (sessionId != null) {
                    CutawayManager.endCutaway(sessionId, initiatorUuid);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                platform.error("Error during cutaway command execution", e);
            }
        }, "voicechat-discord: Cutaway").start();

        source.sendMessage(Component.green("Cutaway command initiated! Teleporting in ")
                .append(Component.aqua(String.valueOf(delaySeconds)))
                .append(Component.green(" second(s).")));
    }

    private record LinkedGroup(UUID groupId, DiscordBot bot) {
        boolean isOwnedBy(CommandSource source) {
            ServerPlayer player = source.player();
            UUID owner = GroupManager.groupOwnerMap.get(groupId);
            return player != null && player.getUuid().equals(owner);
        }
    }

    /** The sender's group and its Discord bot, or null (after telling the sender why). */
    private static LinkedGroup requireLinkedGroup(CommandSource source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return null;
        Group group = GroupManager.getGroupOf(player.getUuid());
        DiscordBot bot = group == null ? null : GroupManager.groupBotMap.get(group.getId());
        if (bot == null) {
            source.sendMessage(Component.red("You are not in a voice chat group linked to a Discord VC."));
            return null;
        }
        return new LinkedGroup(group.getId(), bot);
    }

    private static ServerPlayer requirePlayer(CommandSource source) {
        ServerPlayer player = source.player();
        if (player == null) {
            source.sendMessage(Component.red("Only players can use this command."));
        }
        return player;
    }

    private static void run(CommandSource source, Runnable command) {
        try {
            command.run();
        } catch (Throwable e) {
            platform.error("An error occurred when running a command", e);
            source.sendMessage(Component.red("An error occurred when running the command. Please tell a server admin to check the console."));
        }
    }

    private static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length)).trim();
    }

    /** Parses an int in [min, max], or returns -1. */
    private static int parseInt(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max ? parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
