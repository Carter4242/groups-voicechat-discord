package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.RemoveGroupEvent;

import java.util.*;

import static dev.amsam0.voicechatdiscord.Core.api;
import static dev.amsam0.voicechatdiscord.Core.platform;

public final class GroupManager {
    public static final Map<UUID, List<ServerPlayer>> groupPlayerMap = new HashMap<>();
    // Map groupId -> DiscordBot
    public static final Map<UUID, DiscordBot> groupBotMap = new HashMap<>();
    // Map: groupId -> (player UUID -> List<StaticAudioChannel>)
    public static final Map<UUID, Map<UUID, List<de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel>>> groupAudioChannels = new HashMap<>();
    // Track groups pending Discord channel creation
    public static final Map<UUID, DiscordBot> pendingGroupCreations = new HashMap<>();
    // Track groups removed before channel creation completes
    public static final Set<UUID> removedBeforeCreation = new HashSet<>();

    private static List<ServerPlayer> getPlayers(Group group) {
        List<ServerPlayer> players = groupPlayerMap.putIfAbsent(group.getId(), new ArrayList<>());
        if (players == null) players = groupPlayerMap.get(group.getId()); // java is bad
        return players;
    }

    private static void createStaticAudioChannelIfFirstGroup(Group group, ServerPlayer player, VoicechatConnection connection) {
        platform.info("[createStaticAudioChannelIfFirstGroup] Attempting to create StaticAudioChannel for player " + player.getUuid() + " in group " + group.getId());
        var level = player.getServerLevel();
        var randomChannelId = java.util.UUID.randomUUID();
        var staticChannel = api.createStaticAudioChannel(randomChannelId, level, connection);
        if (staticChannel != null) {
            groupAudioChannels
                .computeIfAbsent(group.getId(), k -> new HashMap<>())
                .computeIfAbsent(player.getUuid(), k -> new ArrayList<>())
                .add(staticChannel);
            platform.info("Created StaticAudioChannel for player " + player.getUuid() + " in group " + group.getId() + " (channelId=" + randomChannelId + ")");
        } else {
            platform.error("Failed to create StaticAudioChannel for player " + player.getUuid() + " in group " + group.getId() + " (channelId=" + randomChannelId + ")");
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onJoinGroup(JoinGroupEvent event) {
        Group group = event.getGroup();
        // Only handle join if group is tracked in groupBotMap (i.e., is a Discord group)
        if (!groupBotMap.containsKey(group.getId())) {
            platform.info("[onJoinGroup] Skipping group " + group.getName() + " (" + group.getId() + "): not in groupBotMap (not a Discord group)");
            return;
        }
        ServerPlayer player = event.getConnection().getPlayer();

        platform.info("[onJoinGroup] Event fired for group: " + group.getName() + " (" + group.getId() + ") and player: " + platform.getName(player) + " (" + player.getUuid() + ")");
        platform.info("[onJoinGroup] groupId=" + group.getId());

        List<ServerPlayer> players = getPlayers(group);
        platform.info("[onJoinGroup] Current players in group: " + players.size());
        boolean wasPresent = players.stream().anyMatch(serverPlayer -> serverPlayer.getUuid().equals(player.getUuid()));
        if (!wasPresent) {
            platform.info(player.getUuid() + " (" + platform.getName(player) + ") joined " + group.getId() + " (" + group.getName() + ")");
            players.add(player);
            createStaticAudioChannelIfFirstGroup(group, player, event.getConnection());
        } else {
            platform.info(player.getUuid() + " (" + platform.getName(player) + ") already joined " + group.getId() + " (" + group.getName() + ")");
        }
        // Update Discord channel name
        DiscordBot bot = groupBotMap.get(group.getId());
        if (bot != null) {
            bot.updateDiscordVoiceChannelNameAsync(players.size(), group.getName());
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onLeaveGroup(LeaveGroupEvent event) {
        Group group = event.getGroup();
        ServerPlayer player = event.getConnection().getPlayer();

        platform.info(player.getUuid() + " (" + platform.getName(player) + ") left " + group.getId() + " (" + group.getName() + ")");

        // Only handle leave if group is tracked in groupBotMap (i.e., is a Discord group)
        if (!groupBotMap.containsKey(group.getId())) {
            platform.info("[onLeaveGroup] Skipping group " + group.getName() + " (" + group.getId() + "): not in groupBotMap (not a Discord group)");
            return;
        }

        List<ServerPlayer> players = getPlayers(group);
        players.removeIf(p -> p.getUuid().equals(player.getUuid()));
        // Remove StaticAudioChannel for this player if present
        Map<UUID, List<de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel>> channels = groupAudioChannels.get(group.getId());
        if (channels != null) {
            var removed = channels.remove(player.getUuid());
            if (removed != null && !removed.isEmpty()) platform.info("Removed StaticAudioChannels for player " + player.getUuid() + " in group " + group.getId());
        }
        // Update Discord channel name
        DiscordBot bot = groupBotMap.get(group.getId());
        if (bot != null) {
            bot.updateDiscordVoiceChannelNameAsync(players.size(), group.getName());
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onGroupCreated(CreateGroupEvent event) {
        Group group = event.getGroup();
        UUID groupId = group.getId();

        if (group.hasPassword()) {
            platform.info("Not adding group " + group.getName() + " (" + groupId + ") to Discord: group has a password.");
            return;
        }

        VoicechatConnection connection = event.getConnection();
        if (connection == null) {
            platform.info("someone created " + groupId + " (" + group.getName() + ")");
            return;
        }
        ServerPlayer player = connection.getPlayer();

        // Only allow two specific users to create Discord groups
        UUID allowed1 = UUID.fromString("aa1afe1c-0e1d-4b90-9208-1c703f818fdd");
        UUID allowed2 = UUID.fromString("bd50ddce-5d31-4380-b1cc-4e11eb78659a");
        if (!player.getUuid().equals(allowed1) && !player.getUuid().equals(allowed2)) {
            platform.info("Not adding group " + group.getName() + " (" + groupId + ") to Discord: creator " + player.getUuid() + " is not allowed.");
            return;
        }

        if (!Core.bots.isEmpty()) {
            DiscordBot found = null;
            for (DiscordBot candidate : Core.bots) {
                if (!candidate.isStarted() && !groupBotMap.containsValue(candidate) && !pendingGroupCreations.containsValue(candidate)) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                platform.warn("No available Discord bots to assign to group " + group.getName() + " (" + groupId + ")! All bots are started or already assigned.\n" +
                    "Bot status: " + Core.bots.stream().map(b -> "started=" + b.isStarted() + ", assigned=" + groupBotMap.containsValue(b)).toList());
                return;
            }
            final DiscordBot bot = found;
            pendingGroupCreations.put(groupId, bot);
            new Thread(() -> {
                if (bot.logIn()) {
                    bot.createDiscordVoiceChannelAsync(group.getName(), discordChannelId -> {
                        if (discordChannelId == null) {
                            platform.error("Failed to create Discord voice channel for group " + group.getName() + " (" + groupId + ")");
                            return;
                        }

                        bot.start();

                        pendingGroupCreations.remove(groupId);
                        synchronized (removedBeforeCreation) {
                            if (removedBeforeCreation.contains(groupId)) {
                                platform.info("Group " + groupId + " (" + group.getName() + ") was removed before Discord channel creation finished. Deleting channel.");
                                bot.deleteDiscordVoiceChannelAsync();
                                removedBeforeCreation.remove(groupId);
                                bot.stop();
                                return;
                            }
                        }

                        bot.startDiscordAudioThread(groupId);
                        groupBotMap.put(groupId, bot);
                        platform.info("Linked groupId " + groupId + " (" + group.getName() + ") to bot (discordChannelId=" + discordChannelId + ")");

                        platform.info(player.getUuid() + " (" + platform.getName(player) + ") created " + groupId + " (" + group.getName() + ")");

                        List<ServerPlayer> players = getPlayers(group);
                        players.add(player);
                        createStaticAudioChannelIfFirstGroup(group, player, connection);
                    });
                } else {
                    platform.error("Failed to login to Discord for group " + group.getName() + " (" + groupId + ")");
                    pendingGroupCreations.remove(groupId);
                }
            }, "voicechat-discord: Bot AutoStart for Group").start();
        } else {
            platform.warn("No available Discord bots to assign to group " + group.getName() + " (" + groupId + ")");
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onGroupRemoved(RemoveGroupEvent event) {
        Group group = event.getGroup();
        UUID groupId = group.getId();

        platform.info("onGroupRemoved: Group removed: " + groupId + ", " + group.getName() + ")");

        // If group is still pending creation, mark for deletion after creation
        if (pendingGroupCreations.containsKey(groupId)) {
            synchronized (removedBeforeCreation) {
                removedBeforeCreation.add(groupId);
            }
            platform.info("onGroupRemoved: Group " + groupId + " is pending Discord channel creation. Will delete after creation.");
        }

        DiscordBot bot = groupBotMap.remove(groupId);
        if (bot != null) {
            platform.info("onGroupRemoved: Stopping Discord bot for group: " + group.getName() + ")");
            bot.stop();
            platform.info("onGroupRemoved: Stopped Discord bot for group: " + group.getName() + ")");
            bot.deleteDiscordVoiceChannelAsync();
        }
        
        groupAudioChannels.remove(groupId);
        groupPlayerMap.remove(groupId);
    }
}
