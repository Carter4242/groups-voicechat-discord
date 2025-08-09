package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.RemoveGroupEvent;
import dev.amsam0.voicechatdiscord.util.BiMap;

import java.util.*;

import static dev.amsam0.voicechatdiscord.Core.api;
import static dev.amsam0.voicechatdiscord.Core.platform;

public final class GroupManager {
    public static final Map<UUID, List<ServerPlayer>> groupPlayers = new HashMap<>();

    // Tracks the first group created on the server
    public static UUID firstGroupId = null;

    // Map: groupId -> (player UUID -> List<StaticAudioChannel>)
    public static final Map<UUID, Map<UUID, List<de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel>>> groupAudioChannels = new HashMap<>();

    private static List<ServerPlayer> getPlayers(Group group) {
        List<ServerPlayer> players = groupPlayers.putIfAbsent(group.getId(), new ArrayList<>());
        if (players == null) players = groupPlayers.get(group.getId()); // java is bad
        return players;
    }

    private static void createStaticAudioChannelIfFirstGroup(Group group, ServerPlayer player, VoicechatConnection connection) {
        if (firstGroupId != null && firstGroupId.equals(group.getId())) {
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
        } else {
            platform.info("[createStaticAudioChannelIfFirstGroup] Not first group or firstGroupId not set, skipping StaticAudioChannel creation.");
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onJoinGroup(JoinGroupEvent event) {
        Group group = event.getGroup();
        ServerPlayer player = event.getConnection().getPlayer();

        platform.info("[onJoinGroup] Event fired for group: " + group.getName() + " (" + group.getId() + ") and player: " + platform.getName(player) + " (" + player.getUuid() + ")");
        platform.info("[onJoinGroup] firstGroupId=" + firstGroupId + ", groupId=" + group.getId());

        List<ServerPlayer> players = getPlayers(group);
        platform.info("[onJoinGroup] Current players in group: " + players.size());
        if (players.stream().noneMatch(serverPlayer -> serverPlayer.getUuid() == player.getUuid())) {
            platform.info(player.getUuid() + " (" + platform.getName(player) + ") joined " + group.getId() + " (" + group.getName() + ")");
            players.add(player);
            createStaticAudioChannelIfFirstGroup(group, player, event.getConnection());
        } else {
            platform.info(player.getUuid() + " (" + platform.getName(player) + ") already joined " + group.getId() + " (" + group.getName() + ")");
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onLeaveGroup(LeaveGroupEvent event) {
        Group group = event.getGroup();
        ServerPlayer player = event.getConnection().getPlayer();
        if (group == null) {
            for (var groupEntry : groupPlayers.entrySet()) {
                List<ServerPlayer> playerList = groupEntry.getValue();
                if (playerList.stream().anyMatch(serverPlayer -> serverPlayer.getUuid() == player.getUuid())) {
                    UUID playerGroup = groupEntry.getKey();
                    platform.info(player.getUuid() + " (" + platform.getName(player) + ") left " + playerGroup + " (" + api.getGroup(playerGroup).getName() + ")");
                    playerList.remove(player);
                    // Remove StaticAudioChannel for this player if present
                    Map<UUID, List<de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel>> channels = groupAudioChannels.get(playerGroup);
                    if (channels != null) {
                        var removed = channels.remove(player.getUuid());
                        if (removed != null && !removed.isEmpty()) platform.info("Removed StaticAudioChannels for player " + player.getUuid() + " in group " + playerGroup);
                    }
                    return;
                }
            }
            platform.info(player.getUuid() + " (" + platform.getName(player) + ") left a group but we couldn't find the group they left");
            return;
        }

        platform.info(player.getUuid() + " (" + platform.getName(player) + ") left " + group.getId() + " (" + group.getName() + ")");

        List<ServerPlayer> players = getPlayers(group);
        players.remove(player);
        // If this is the first group, remove player from DiscordBot
        if (firstGroupId != null && firstGroupId.equals(group.getId())) {
            // Remove StaticAudioChannel for this player if present
            Map<UUID, List<de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel>> channels = groupAudioChannels.get(group.getId());
            if (channels != null) {
                var removed = channels.remove(player.getUuid());
                if (removed != null && !removed.isEmpty()) platform.info("Removed StaticAudioChannels for player " + player.getUuid() + " in group " + group.getId());
            }
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onGroupCreated(CreateGroupEvent event) {
        Group group = event.getGroup();
        UUID groupId = group.getId();

        if (firstGroupId == null) {
            firstGroupId = groupId;
            platform.info("First group created: " + group.getName() + " (" + groupId + ")");
            // Start Discord bot for the first group
            if (!Core.bots.isEmpty() && !Core.bots.get(0).isStarted()) {
                new Thread(() -> Core.bots.get(0).logInAndStart(null), "voicechat-discord: Bot AutoStart for First Group").start();
                platform.info("Auto-started Discord bot for first group: " + group.getName());
            } else {
                platform.warn("No available Discord bot to auto-start for first group.");
            }
        }

        VoicechatConnection connection = event.getConnection();
        if (connection == null) {
            platform.info("someone created " + groupId + " (" + group.getName() + ")");
            return;
        }
        ServerPlayer player = connection.getPlayer();

        platform.info(player.getUuid() + " (" + platform.getName(player) + ") created " + groupId + " (" + group.getName() + ")");

        List<ServerPlayer> players = getPlayers(group);
        players.add(player);
        createStaticAudioChannelIfFirstGroup(group, player, connection);
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onGroupRemoved(RemoveGroupEvent event) {
        Group group = event.getGroup();
        UUID groupId = group.getId();

        platform.info("onGroupRemoved: Group removed: " + groupId + ", " + group.getName() + ")");

        if (firstGroupId != null && firstGroupId.equals(groupId)) {
            platform.info("onGroupRemoved: First group removed: " + group.getName() + " (" + groupId + ")");
            firstGroupId = null;
            // Stop Discord bot for the first group
            if (!Core.bots.isEmpty() && Core.bots.get(0).isStarted()) {
                platform.info("onGroupRemoved: Stopping Discord bot for first group: " + group.getName() + " (vc_id=" + Core.bots.get(0).vcId + ")");
                Core.bots.get(0).stop();
                platform.info("onGroupRemoved: Stopped Discord bot for first group: " + group.getName() + " (vc_id=" + Core.bots.get(0).vcId + ")");
            }
            // Remove all StaticAudioChannels for this group
            groupAudioChannels.remove(groupId);
        }

        groupPlayers.remove(groupId);
    }
}
