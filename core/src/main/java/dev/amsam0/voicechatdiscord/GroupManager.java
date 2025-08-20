package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.RemoveGroupEvent;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static dev.amsam0.voicechatdiscord.Core.platform;

public final class GroupManager {

    // Discord userId -> current channelId
    public static final Map<Long, Long> discordUserChannelMap = new ConcurrentHashMap<>();
    // Discord userId -> username
    public static final Map<Long, String> discordUserNameMap = new ConcurrentHashMap<>();

    // Map groupId -> List of players
    public static final Map<UUID, List<ServerPlayer>> groupPlayerMap = new HashMap<>();
    // Map groupId -> owner UUID
    public static final Map<UUID, UUID> groupOwnerMap = new HashMap<>();
    // Queue join events for groups whose Discord channel is still being created
    public static final Map<UUID, List<JoinGroupEvent>> pendingJoinEvents = new HashMap<>();

    // Map groupId -> (player UUID -> (username -> StaticAudioChannel))
    public static final Map<UUID, Map<UUID, Map<String, StaticAudioChannel>>> groupAudioChannels = new HashMap<>();

    // Map groupId -> DiscordBot
    public static final Map<UUID, DiscordBot> groupBotMap = new HashMap<>();
    // Track groups pending Discord channel creation
    public static final Map<UUID, DiscordBot> pendingGroupCreations = new HashMap<>();
    // Track groups removed before channel creation completes
    public static final Set<UUID> removedBeforeCreation = new HashSet<>();

    // Timer for periodic VC name updates
    private static final java.util.Timer vcUpdateTimer = new java.util.Timer(true);
    private static final long VC_UPDATE_INTERVAL_MS = 310_000; // 5m 10s

    static {
        // Start periodic VC name updates for all Discord groups
        vcUpdateTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                for (UUID groupId : groupBotMap.keySet()) {
                    DiscordBot bot = groupBotMap.get(groupId);
                    if (bot != null) {
                        List<ServerPlayer> players = groupPlayerMap.get(groupId);
                        int playerCount = (players != null) ? players.size() : 0;
                        if (playerCount > 0) {
                            String groupName = Core.api.getGroup(groupId).getName();
                            bot.updateDiscordVoiceChannelNameAsync(playerCount, groupName);
                        }
                    }
                }
            }
        }, 0, VC_UPDATE_INTERVAL_MS);
    }

    private static List<ServerPlayer> getPlayers(Group group) {
        List<ServerPlayer> players = groupPlayerMap.putIfAbsent(group.getId(), new ArrayList<>());
        if (players == null) players = groupPlayerMap.get(group.getId()); // java is bad
        return players;
    }

    private static void handlePlayerJoin(Group group, ServerPlayer player, VoicechatConnection connection, DiscordBot bot, int playerCount) {
        platform.info("[handlePlayerJoin] Handling join for player " + player.getUuid() + " in group " + group.getId());
        
        // Create a StaticAudioChannel for every Discord user currently in the VC for this group
        if (bot != null) {
            Long discordChannelId = bot.getDiscordChannelId();
            if (discordChannelId != null) {
                for (var entry : discordUserChannelMap.entrySet()) {
                    if (entry.getValue() != null && entry.getValue().equals(discordChannelId)) {
                        String username = discordUserNameMap.get(entry.getKey());
                        if (username != null && !username.isEmpty()) {
                            var playerId = player.getUuid();
                            var level = player.getServerLevel();
                            var staticChannel = Core.api.createStaticAudioChannel(UUID.randomUUID(), level, connection);
                            if (staticChannel != null) {
                                groupAudioChannels
                                    .computeIfAbsent(group.getId(), k -> new HashMap<>())
                                    .computeIfAbsent(playerId, k -> new HashMap<>())
                                    .put(username, staticChannel);
                                platform.info("Created StaticAudioChannel for Discord user '" + username + "' and player " + playerId + " in group " + group.getId());
                            } else {
                                platform.error("Failed to create StaticAudioChannel for Discord user '" + username + "' and player " + playerId + " in group " + group.getId());
                            }
                        }
                    }
                }
            }

            String joinMsg = ">> **" + platform.getName(player) + "** joined the group! (" + playerCount + (playerCount == 1 ? " Player" : " Players") + ")";
            bot.sendDiscordTextMessageAsync(joinMsg);
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onJoinGroup(JoinGroupEvent event) {
        Group group = event.getGroup();
        UUID groupId = group.getId();
        // If group is still pending Discord channel creation, queue the join event
        if (pendingGroupCreations.containsKey(groupId)) {
            platform.info("[onJoinGroup] Group " + group.getName() + " (" + groupId + ") is pending Discord channel creation; queuing join event for player " + platform.getName(event.getConnection().getPlayer()) + " (" + event.getConnection().getPlayer().getUuid() + ")");
            synchronized (pendingJoinEvents) {
                pendingJoinEvents.computeIfAbsent(groupId, k -> new ArrayList<>()).add(event);
            }
            return;
        }
        // Only handle join if group is tracked in groupBotMap (i.e., is a Discord group)
        if (!groupBotMap.containsKey(groupId)) {
            platform.info("[onJoinGroup] Skipping group " + group.getName() + " (" + groupId + "): not in groupBotMap (not a Discord group)");
            return;
        }
        ServerPlayer player = event.getConnection().getPlayer();

        platform.info("[onJoinGroup] Event fired for group: " + group.getName() + " (" + group.getId() + ") and player: " + platform.getName(player) + " (" + player.getUuid() + ")");
        platform.info("[onJoinGroup] groupId=" + group.getId());

        List<ServerPlayer> players = getPlayers(group);
        platform.info("[onJoinGroup] Current players in group: " + players.size());
        boolean wasPresent = players.stream().anyMatch(serverPlayer -> serverPlayer.getUuid().equals(player.getUuid()));
        DiscordBot bot = groupBotMap.get(group.getId());
        if (!wasPresent) {
            platform.info(player.getUuid() + " (" + platform.getName(player) + ") joined " + group.getId() + " (" + group.getName() + ")");
            players.add(player);
            handlePlayerJoin(group, player, event.getConnection(), bot, players.size());
        } else {
            platform.info(player.getUuid() + " (" + platform.getName(player) + ") already joined " + group.getId() + " (" + group.getName() + ")");
        }

        if (bot != null) {
            // Send a list of all users currently in the Discord VC for this group
            Long discordChannelId = bot.getDiscordChannelId();
            if (discordChannelId != null) {
                List<String> discordUsers = new ArrayList<>();
                for (var entry : discordUserChannelMap.entrySet()) {
                    if (entry.getValue() != null && entry.getValue().equals(discordChannelId)) {
                        String name = discordUserNameMap.get(entry.getKey());
                        if (name != null) discordUsers.add(name);
                    }
                }
                if (!discordUsers.isEmpty()) {
                    List<Component> components = new ArrayList<>();
                    components.add(Component.blue("[Discord] "));
                    components.add(Component.white("Users in Discord VC: "));
                    for (int i = 0; i < discordUsers.size(); i++) {
                        if (i > 0) components.add(Component.white(", "));
                        components.add(Component.gold(discordUsers.get(i)));
                    }
                    platform.sendMessage(player, components.toArray(new Component[0]));
                }
            }
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

        // Remove all StaticAudioChannels for this player if present
        Map<UUID, Map<String, StaticAudioChannel>> channels = groupAudioChannels.get(group.getId());
        if (channels != null) {
            var removed = channels.remove(player.getUuid());
            if (removed != null && !removed.isEmpty()) platform.info("Removed StaticAudioChannels for player " + player.getUuid() + " in group " + group.getId());
        }

        if (!players.isEmpty()) {
            DiscordBot bot = groupBotMap.get(group.getId());
            if (bot != null) {
                String leaveMsg = ">> **" + platform.getName(player) + "** left the group. (" + players.size() + (players.size() == 1 ? " Player" : " Players") + ")";
                bot.sendDiscordTextMessageAsync(leaveMsg);
            }
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

        // Track the owner of the group
        groupOwnerMap.put(groupId, player.getUuid());

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
                        handlePlayerJoin(group, player, connection, bot, players.size());

                        // Process any queued join events for this group
                        List<JoinGroupEvent> queued;
                        synchronized (pendingJoinEvents) {
                            queued = pendingJoinEvents.remove(groupId);
                        }
                        if (queued != null) {
                            platform.info("Processing " + queued.size() + " queued join events for group " + groupId + " (" + group.getName() + ")");
                            for (JoinGroupEvent joinEvent : queued) {
                                // Re-dispatch the join event for normal handling
                                onJoinGroup(joinEvent);
                            }
                        }
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

        // Clean up all Discord users in the group's VC as if they left
        DiscordBot bot = groupBotMap.get(groupId);
        if (bot != null) {
            Long discordChannelId = bot.getDiscordChannelId();
            if (discordChannelId != null) {
                // Find all Discord users in this VC
                java.util.List<Long> usersInChannel = new java.util.ArrayList<>();
                for (var entry : discordUserChannelMap.entrySet()) {
                    if (discordChannelId.equals(entry.getValue())) {
                        usersInChannel.add(entry.getKey());
                    }
                }
                for (Long discordUserId : usersInChannel) {
                    String username = discordUserNameMap.get(discordUserId);
                    if (username != null) {
                        // Simulate leave: remove channels and unregister category
                        DiscordBot.removeDiscordUserChannelsFromGroup(groupId, username);
                        String categoryId = DiscordBot.discordUserCategoryMap.remove(discordUserId);
                        if (categoryId != null) {
                            Core.api.unregisterVolumeCategory(categoryId);
                            platform.info("Unregistered volume category for Discord user '" + username + "' (ID: " + discordUserId + ")");
                        }
                    }
                    discordUserChannelMap.remove(discordUserId);
                    discordUserNameMap.remove(discordUserId);
                }
            }
        }

        bot = groupBotMap.remove(groupId);
        if (bot != null) {
            platform.info("onGroupRemoved: Stopping Discord bot for group: " + group.getName() + ")");
            bot.stop();
            platform.info("onGroupRemoved: Stopped Discord bot for group: " + group.getName() + ")");
        }

        groupAudioChannels.remove(groupId);
        groupPlayerMap.remove(groupId);
        groupOwnerMap.remove(groupId);
    }
}
