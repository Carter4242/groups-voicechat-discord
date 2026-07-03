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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static dev.amsam0.voicechatdiscord.Core.platform;

public final class GroupManager {
    private static final Object permanentGroupInitLock = new Object();
    private static volatile UUID permanentGroupId = null;

    // Discord userId -> current channelId
    public static final Map<Long, Long> discordUserChannelMap = new ConcurrentHashMap<>();
    // Discord userId -> username
    public static final Map<Long, String> discordUserNameMap = new ConcurrentHashMap<>();

    // Map groupId -> List of players
    public static final Map<UUID, List<ServerPlayer>> groupPlayerMap = new ConcurrentHashMap<>();
    // Map groupId -> owner UUID
    public static final Map<UUID, UUID> groupOwnerMap = new ConcurrentHashMap<>();
    // Queue join events for groups whose Discord channel is still being created
    public static final Map<UUID, List<JoinGroupEvent>> pendingJoinEvents = new HashMap<>();

    // Map groupId -> (player UUID -> (Discord user ID -> StaticAudioChannel))
    public static final Map<UUID, Map<UUID, Map<Long, StaticAudioChannel>>> groupAudioChannels = new ConcurrentHashMap<>();

    // Map groupId -> DiscordBot
    public static final Map<UUID, DiscordBot> groupBotMap = new ConcurrentHashMap<>();
    // Track groups pending Discord channel creation
    public static final Map<UUID, DiscordBot> pendingGroupCreations = new ConcurrentHashMap<>();
    // Track groups removed before channel creation completes
    public static final Set<UUID> removedBeforeCreation = new HashSet<>();

    // Track last player count for each group to avoid unnecessary renames
    private static final Map<UUID, Integer> lastPlayerCounts = new ConcurrentHashMap<>();
    // Timer for periodic VC name updates
    private static final java.util.Timer vcUpdateTimer = new java.util.Timer(true);
    private static final long VC_UPDATE_INTERVAL_MS = 310_000; // 5m 10s

    static {
        // Start periodic VC name updates for all Discord groups
        vcUpdateTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                for (UUID groupId : groupBotMap.keySet()) {
                    updateDiscordChannelNameIfNeeded(groupId);
                }
            }
        }, 0, VC_UPDATE_INTERVAL_MS);
    }

    private static List<ServerPlayer> getPlayers(Group group) {
        List<ServerPlayer> players = groupPlayerMap.putIfAbsent(group.getId(), new CopyOnWriteArrayList<>());
        if (players == null) players = groupPlayerMap.get(group.getId());
        return players;
    }

    public static boolean isPermanentGroup(UUID groupId) {
        return groupId != null && groupId.equals(permanentGroupId);
    }

    private static boolean isConfiguredPermanentGroup(Group group) {
        return group != null
            && Core.permanentMcGroupName.equals(group.getName())
            && !group.hasPassword()
            && group.isPersistent()
            && group.getType() == Group.Type.OPEN;
    }

    private static String getDiscordChannelBaseName(UUID groupId, String fallbackName) {
        return isPermanentGroup(groupId) ? Core.permanentDiscordChannelName : fallbackName;
    }

    private static DiscordBot findAvailableBot() {
        for (DiscordBot candidate : Core.bots) {
            if (!candidate.isStarted() && !groupBotMap.containsValue(candidate) && !pendingGroupCreations.containsValue(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static UUID getGroupIdForBot(DiscordBot bot) {
        if (bot == null) return null;
        for (var entry : groupBotMap.entrySet()) {
            if (entry.getValue() == bot) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static void updateDiscordChannelNameIfNeeded(UUID groupId) {
        if (Core.api == null || groupId == null) return;
        DiscordBot bot = groupBotMap.get(groupId);
        if (bot == null) return;

        List<ServerPlayer> players = groupPlayerMap.get(groupId);
        int playerCount = (players != null) ? players.size() : 0;
        boolean permanent = isPermanentGroup(groupId);
        if (!permanent && playerCount <= 0) return;
        Integer lastCount = lastPlayerCounts.get(groupId);
        if (lastCount != null && lastCount == playerCount) return;

        String baseName;
        if (permanent) {
            baseName = Core.permanentDiscordChannelName;
        } else {
            Group group = Core.api.getGroup(groupId);
            if (group == null) return;
            try {
                baseName = group.getName();
            } catch (Throwable t) {
                platform.debug("Skipping Discord channel rename for group " + groupId + ": failed to read group name", t);
                return;
            }
        }

        bot.updateDiscordVoiceChannelNameAsync(playerCount, baseName);
        lastPlayerCounts.put(groupId, playerCount);
    }

    public static void updatePermanentChannelNameForShutdown(DiscordBot bot) {
        if (bot == null) return;
        UUID groupId = getGroupIdForBot(bot);
        if (!isPermanentGroup(groupId)) return;
        bot.updateDiscordVoiceChannelNameAsync(0, Core.permanentDiscordChannelName);
        lastPlayerCounts.put(groupId, 0);
    }

    private static void syncPermanentGroupVoiceConnection(UUID groupId) {
        if (!isPermanentGroup(groupId)) return;

        DiscordBot bot = groupBotMap.get(groupId);
        if (bot == null) return;

        new Thread(() -> {
            bot.getLifecycleLock().lock();
            try {
                // Read the player count under the lock so a stale count from a
                // just-superseded join/leave can't disconnect an active bot.
                List<ServerPlayer> players = groupPlayerMap.get(groupId);
                int playerCount = (players != null) ? players.size() : 0;

                if (playerCount > 0) {
                    if (!bot.isStarted()) {
                        platform.debug("Permanent group has its first player; connecting bot to Discord VC.");
                        if (startVoiceWithRetry(bot)) {
                            bot.startDiscordAudioThread(groupId);
                        } else {
                            platform.error("Failed to connect permanent group bot to Discord VC; will retry on the next group join/leave.");
                        }
                    }
                } else {
                    if (bot.isStarted()) {
                        platform.debug("Permanent group has no players; disconnecting bot from Discord VC.");
                        bot.disconnect();
                        bot.stop(false);
                    }
                }
            } finally {
                bot.getLifecycleLock().unlock();
            }
        }, "voicechat-discord: Permanent Group Voice Sync").start();
    }

    private static void processQueuedJoinEvents(UUID groupId, Group group) {
        List<JoinGroupEvent> queued;
        synchronized (pendingJoinEvents) {
            queued = pendingJoinEvents.remove(groupId);
        }
        if (queued == null) return;

        platform.debug("Processing " + queued.size() + " queued join events for group " + groupId + " (" + group.getName() + ")");
        for (JoinGroupEvent joinEvent : queued) {
            onJoinGroup(joinEvent);
        }
    }

    private static void startPermanentGroupBridge(Group group) {
        UUID groupId = group.getId();
        synchronized (permanentGroupInitLock) {
            if (groupBotMap.containsKey(groupId) || pendingGroupCreations.containsKey(groupId)) {
                return;
            }
            DiscordBot bot = findAvailableBot();
            if (bot == null) {
                platform.warn("No available Discord bots to assign to permanent group " + group.getName() + " (" + groupId + ")");
                return;
            }
            pendingGroupCreations.put(groupId, bot);
            new Thread(() -> {
                bot.getLifecycleLock().lock();
                try {
                    if (bot.logIn()) {
                        long permanentChannelId = Core.permanentDiscordChannelId;
                        if (permanentChannelId <= 0L) {
                            platform.error("Cannot start permanent group bridge: permanent_discord_channel_id is invalid (" + permanentChannelId + ")");
                            pendingGroupCreations.remove(groupId);
                            bot.stop(false);
                            return;
                        }
                        bot.setManagedDiscordVoiceChannel(permanentChannelId);
                        // Note: the bot only joins voice once players are in the group
                        // (syncPermanentGroupVoiceConnection below); start() here connects
                        // early so the channel is claimed, and failures are non-fatal.
                        bot.start();

                        pendingGroupCreations.remove(groupId);
                        synchronized (removedBeforeCreation) {
                            if (removedBeforeCreation.contains(groupId)) {
                                platform.debug("Permanent group " + groupId + " was removed before startup finished.");
                                removedBeforeCreation.remove(groupId);
                                bot.disconnect();
                                bot.stop(false);
                                return;
                            }
                        }

                        groupPlayerMap.putIfAbsent(groupId, new CopyOnWriteArrayList<>());
                        groupBotMap.put(groupId, bot);
                        platform.info("Linked permanent group " + group.getName() + " (" + groupId + ") to Discord channel " + Core.permanentDiscordChannelId + "; bot will join voice when players are in the group.");
                    } else {
                        platform.error("Failed to login to Discord for permanent group " + group.getName() + " (" + groupId + ")");
                        pendingGroupCreations.remove(groupId);
                        return;
                    }
                } finally {
                    bot.getLifecycleLock().unlock();
                }
                repopulateGroupPlayers(groupId, group);
                processQueuedJoinEvents(groupId, group);
                syncPermanentGroupVoiceConnection(groupId);
            }, "voicechat-discord: Permanent Group Bot Start").start();
        }
    }

    public static void ensurePermanentGroup() {
        if (Core.api == null) {
            platform.debug("Skipping permanent group initialization: VoiceChat API not ready yet.");
            return;
        }

        Group permanentGroup = null;
        Group wrongTypeGroup = null;
        for (Group group : Core.api.getGroups()) {
            if (Core.permanentMcGroupName.equals(group.getName())) {
                if (isConfiguredPermanentGroup(group)) {
                    permanentGroup = group;
                    break;
                }
                wrongTypeGroup = group;
            }
        }

        if (permanentGroup == null && wrongTypeGroup != null) {
            platform.warn("Found group named '" + Core.permanentMcGroupName + "' but it is not OPEN+persistent+no-password. Creating the required permanent group.");
        }

        if (permanentGroup == null) {
            try {
                permanentGroup = Core.api.groupBuilder()
                    .setName(Core.permanentMcGroupName)
                    .setPassword(null)
                    .setPersistent(true)
                    .setType(Group.Type.OPEN)
                    .build();
                platform.info("Created permanent voicechat group '" + Core.permanentMcGroupName + "' (" + permanentGroup.getId() + ")");
            } catch (Throwable t) {
                platform.error("Failed to create permanent voicechat group '" + Core.permanentMcGroupName + "'", t);
                return;
            }
        }

        permanentGroupId = permanentGroup.getId();
        groupPlayerMap.putIfAbsent(permanentGroupId, new CopyOnWriteArrayList<>());
        startPermanentGroupBridge(permanentGroup);
    }

    /**
     * Re-adds online players that are already members of the given voicechat group
     * to our tracking map. Needed after a config reload, which clears all tracked
     * state while players may still be sitting in the (persistent) group.
     * Quiet: no join messages are sent; audio channels are created lazily.
     */
    private static void repopulateGroupPlayers(UUID groupId, Group group) {
        try {
            List<ServerPlayer> players = groupPlayerMap.computeIfAbsent(groupId, k -> new CopyOnWriteArrayList<>());
            for (UUID playerUuid : platform.getOnlinePlayerUuids()) {
                VoicechatConnection connection = Core.api.getConnectionOf(playerUuid);
                if (connection == null) continue;
                Group playerGroup = connection.getGroup();
                if (playerGroup == null || !groupId.equals(playerGroup.getId())) continue;
                ServerPlayer player = connection.getPlayer();
                if (player == null) continue;
                boolean present = players.stream().anyMatch(p -> p.getUuid().equals(playerUuid));
                if (!present) {
                    players.add(player);
                    platform.debug("Repopulated player " + playerUuid + " into group " + groupId + " (" + group.getName() + ")");
                }
            }
        } catch (Throwable t) {
            platform.error("Failed to repopulate players for group " + groupId, t);
        }
    }

    /**
     * Starts the bot's voice connection, retrying once after a short delay.
     * Discord can cancel a join request that races the gateway echo of a
     * just-completed disconnect ("request was cancelled/dropped"), so a single
     * spaced retry resolves the common restart race.
     */
    public static boolean startVoiceWithRetry(DiscordBot bot) {
        if (bot.start()) return true;
        platform.warn("Voice connect attempt failed (vcid=" + bot.getDiscordChannelId() + "); retrying in 1 second...");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return bot.start();
    }

    /**
     * Restarts a bot's Discord session because the voice receive path is corrupted
     * (detected by the native watchdog). Skips silently if a lifecycle operation
     * (e.g. a manual restart) is already in progress.
     */
    public static void autoRestartBot(DiscordBot bot) {
        UUID groupId = getGroupIdForBot(bot);
        if (groupId == null) return;
        new Thread(() -> {
            if (!bot.getLifecycleLock().tryLock()) {
                platform.warn("Voice receive session corrupted, but a bot lifecycle operation is already in progress; skipping auto-restart.");
                return;
            }
            try {
                platform.warn("Detected corrupted Discord voice receive session (vcid=" + bot.getDiscordChannelId() + "); automatically restarting the bot.");
                bot.disconnect();
                bot.stop(false);
                try {
                    // Give Discord time to process the disconnect before rejoining,
                    // or the join can be cancelled by the leave's gateway echo.
                    Thread.sleep(750);
                } catch (InterruptedException ignored) {}
                if (bot.logIn() && startVoiceWithRetry(bot)) {
                    bot.startDiscordAudioThread(groupId);
                    platform.info("Auto-restart of Discord bot complete (vcid=" + bot.getDiscordChannelId() + ").");
                } else {
                    platform.error("Auto-restart of Discord bot failed (vcid=" + bot.getDiscordChannelId() + "). A manual /dvcgroup restart may be needed.");
                }
            } catch (Throwable t) {
                platform.error("Auto-restart of Discord bot failed", t);
            } finally {
                bot.getLifecycleLock().unlock();
            }
        }, "voicechat-discord: Auto Restart").start();
    }

    /**
     * Re-logs a bot in after its gateway connection died permanently, with
     * backoff (the death may be a network outage). Reconnects voice afterwards
     * if the bot's group should be active. Holds the lifecycle lock for the
     * whole recovery so manual restarts don't interleave.
     */
    public static void recoverFromGatewayDeath(DiscordBot bot) {
        new Thread(() -> {
            if (!bot.getLifecycleLock().tryLock()) {
                platform.warn("Gateway died, but another bot lifecycle operation is in progress; skipping auto re-login (it may recover the bot itself).");
                return;
            }
            try {
                // Rust state is already NotLoggedIn; this just stops the audio thread.
                bot.stop(false);

                long[] delaysMs = {5_000, 15_000, 30_000, 60_000, 120_000};
                for (int attempt = 0; attempt < delaysMs.length; attempt++) {
                    try {
                        Thread.sleep(delaysMs[attempt]);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (bot.isFreed()) return;
                    if (!bot.logIn()) {
                        platform.warn("Gateway re-login attempt " + (attempt + 1) + "/" + delaysMs.length + " failed; will retry.");
                        continue;
                    }

                    UUID groupId = getGroupIdForBot(bot);
                    if (groupId == null) {
                        platform.info("Gateway re-login succeeded (vcid=" + bot.getDiscordChannelId() + "); bot has no linked group, staying idle.");
                        return;
                    }
                    List<ServerPlayer> players = groupPlayerMap.get(groupId);
                    boolean voiceNeeded = !isPermanentGroup(groupId) || (players != null && !players.isEmpty());
                    if (!voiceNeeded) {
                        platform.info("Gateway re-login succeeded (vcid=" + bot.getDiscordChannelId() + "); permanent group is empty, voice stays disconnected.");
                        return;
                    }
                    if (startVoiceWithRetry(bot)) {
                        bot.startDiscordAudioThread(groupId);
                        platform.info("Gateway re-login succeeded and voice reconnected (vcid=" + bot.getDiscordChannelId() + ").");
                    } else {
                        platform.error("Gateway re-login succeeded but the voice reconnect failed (vcid=" + bot.getDiscordChannelId() + "). Use /dvcgroup restart.");
                    }
                    return;
                }
                platform.error("Gave up re-logging in after " + delaysMs.length + " attempts (vcid=" + bot.getDiscordChannelId() + "). Use /dvcgroup restart or restart the server.");
            } catch (Throwable t) {
                platform.error("Gateway death recovery failed", t);
            } finally {
                bot.getLifecycleLock().unlock();
            }
        }, "voicechat-discord: Gateway Recovery").start();
    }

    /**
     * Removes the Discord-side link for a group: Discord user audio channels and
     * volume categories, plus the group->bot association. Used when the bot is
     * stopped manually (dvcgroup stop) and when the group is removed.
     * Does not touch groupPlayerMap/groupOwnerMap: the voicechat group may still exist.
     */
    public static void unlinkGroupFromDiscord(UUID groupId) {
        DiscordBot bot = groupBotMap.get(groupId);
        if (bot != null) {
            Long discordChannelId = bot.getDiscordChannelId();
            if (discordChannelId != null) {
                java.util.List<Long> usersInChannel = new java.util.ArrayList<>();
                for (var entry : discordUserChannelMap.entrySet()) {
                    if (discordChannelId.equals(entry.getValue())) {
                        usersInChannel.add(entry.getKey());
                    }
                }
                for (Long discordUserId : usersInChannel) {
                    String username = discordUserNameMap.get(discordUserId);
                    if (username != null) {
                        DiscordBot.removeDiscordUserChannelsFromGroup(groupId, discordUserId);
                        String categoryId = DiscordBot.discordUserCategoryMap.remove(discordUserId);
                        if (categoryId != null) {
                            Core.api.unregisterVolumeCategory(categoryId);
                            platform.debug("Unregistered volume category for Discord user '" + username + "' (ID: " + discordUserId + ")");
                        }
                    }
                    discordUserChannelMap.remove(discordUserId);
                    discordUserNameMap.remove(discordUserId);
                }
            }
        }
        groupBotMap.remove(groupId);
        groupAudioChannels.remove(groupId);
        lastPlayerCounts.remove(groupId);
    }

    public static void clearTrackedState() {
        discordUserChannelMap.clear();
        discordUserNameMap.clear();
        groupPlayerMap.clear();
        groupOwnerMap.clear();
        synchronized (pendingJoinEvents) {
            pendingJoinEvents.clear();
        }
        groupAudioChannels.clear();
        groupBotMap.clear();
        pendingGroupCreations.clear();
        synchronized (removedBeforeCreation) {
            removedBeforeCreation.clear();
        }
        lastPlayerCounts.clear();
        permanentGroupId = null;
    }

    private static void handlePlayerJoin(Group group, ServerPlayer player, VoicechatConnection connection, DiscordBot bot, int playerCount) {
        platform.debug("[handlePlayerJoin] Handling join for player " + player.getUuid() + " in group " + group.getId());
        
        // Create a StaticAudioChannel for every Discord user currently in the VC for this group
        if (bot != null) {
            Long discordChannelId = bot.getDiscordChannelId();
            if (discordChannelId != null) {
                for (var entry : discordUserChannelMap.entrySet()) {
                    if (entry.getValue() != null && entry.getValue().equals(discordChannelId)) {
                        Long discordUserId = entry.getKey();
                        String username = discordUserNameMap.get(discordUserId);
                        if (username != null && !username.isEmpty()) {
                            var playerId = player.getUuid();
                            var level = player.getServerLevel();
                            var staticChannel = Core.api.createStaticAudioChannel(UUID.randomUUID(), level, connection);
                            if (staticChannel != null) {
                                String categoryId = DiscordBot.discordUserCategoryMap.get(discordUserId);
                                if (categoryId != null) {
                                    staticChannel.setCategory(categoryId);
                                }
                                groupAudioChannels
                                    .computeIfAbsent(group.getId(), k -> new ConcurrentHashMap<>())
                                    .computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                                    .put(discordUserId, staticChannel);
                                platform.debug("Created StaticAudioChannel for Discord user '" + username + "' (ID: " + discordUserId + ") and player " + playerId + " in group " + group.getId());
                            } else {
                                platform.error("Failed to create StaticAudioChannel for Discord user '" + username + "' (ID: " + discordUserId + ") and player " + playerId + " in group " + group.getId());
                            }
                        }
                    }
                }
            }

            String joinMsg = "[<t:" + (System.currentTimeMillis() / 1000) + ":t>] **" + platform.getName(player) + "** joined the group! (" + playerCount + (playerCount == 1 ? " Player" : " Players") + ")";
            bot.sendDiscordTextMessageAsync(joinMsg, true);
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onJoinGroup(JoinGroupEvent event) {
        Group group = event.getGroup();
        UUID groupId = group.getId();
        ServerPlayer player = event.getConnection().getPlayer();
        
        // Check if player was in a different Discord group before and remove them
        UUID previousDiscordGroup = null;
        for (Map.Entry<UUID, List<ServerPlayer>> entry : groupPlayerMap.entrySet()) {
            UUID otherGroupId = entry.getKey();
            if (!otherGroupId.equals(groupId) && groupBotMap.containsKey(otherGroupId)) {
                List<ServerPlayer> otherPlayers = entry.getValue();
                if (otherPlayers.stream().anyMatch(p -> p.getUuid().equals(player.getUuid()))) {
                    previousDiscordGroup = otherGroupId;
                    break;
                }
            }
        }
        
        // Remove player from previous Discord group if they were in one
        if (previousDiscordGroup != null) {
            removePlayerFromGroup(previousDiscordGroup, player.getUuid(), platform.getName(player));
        }
        
        // If group is still pending Discord channel creation, queue the join event
        if (pendingGroupCreations.containsKey(groupId)) {
            platform.debug("[onJoinGroup] Group " + group.getName() + " (" + groupId + ") is pending Discord channel creation; queuing join event for player " + platform.getName(event.getConnection().getPlayer()) + " (" + event.getConnection().getPlayer().getUuid() + ")");
            synchronized (pendingJoinEvents) {
                pendingJoinEvents.computeIfAbsent(groupId, k -> new ArrayList<>()).add(event);
            }
            return;
        }
        // Only handle join if group is tracked in groupBotMap (i.e., is a Discord group)
        if (!groupBotMap.containsKey(groupId)) {
            platform.debug("[onJoinGroup] Skipping group " + group.getName() + " (" + groupId + "): not in groupBotMap (not a Discord group)");
            return;
        }

        platform.debug("[onJoinGroup] Event fired for group: " + group.getName() + " (" + group.getId() + ") and player: " + platform.getName(player) + " (" + player.getUuid() + ")");
        platform.debug("[onJoinGroup] groupId=" + group.getId());

        List<ServerPlayer> players = getPlayers(group);
        platform.debug("[onJoinGroup] Current players in group: " + players.size());
        boolean wasPresent = players.stream().anyMatch(serverPlayer -> serverPlayer.getUuid().equals(player.getUuid()));
        DiscordBot bot = groupBotMap.get(group.getId());
        if (!wasPresent) {
            platform.debug(player.getUuid() + " (" + platform.getName(player) + ") joined " + group.getId() + " (" + group.getName() + ")");
            players.add(player);
            handlePlayerJoin(group, player, event.getConnection(), bot, players.size());
            syncPermanentGroupVoiceConnection(groupId);
        } else {
            platform.debug(player.getUuid() + " (" + platform.getName(player) + ") already joined " + group.getId() + " (" + group.getName() + ")");
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
                        components.add(Component.blue("\n[Discord] ").append(Component.white("Use ")).append(Component.gold("/grm")).append(Component.white(" to chat with the group.")));
                    platform.sendMessage(player, components.toArray(new Component[0]));
                }
            }
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onLeaveGroup(LeaveGroupEvent event) {
        Group group = event.getGroup();
        ServerPlayer player = event.getConnection().getPlayer();

        platform.debug(player.getUuid() + " (" + platform.getName(player) + ") left " + group.getId() + " (" + group.getName() + ")");

        // Only handle leave if group is tracked in groupBotMap (i.e., is a Discord group)
        if (!groupBotMap.containsKey(group.getId())) {
            platform.debug("[onLeaveGroup] Skipping group " + group.getName() + " (" + group.getId() + "): not in groupBotMap (not a Discord group)");
            return;
        }

        removePlayerFromGroup(group.getId(), player.getUuid(), platform.getName(player));
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onGroupCreated(CreateGroupEvent event) {
        Group group = event.getGroup();
        UUID groupId = group.getId();

        if (isConfiguredPermanentGroup(group)) {
            permanentGroupId = groupId;
            groupPlayerMap.putIfAbsent(groupId, new CopyOnWriteArrayList<>());
            startPermanentGroupBridge(group);
            return;
        }

        if (group.hasPassword()) {
            platform.info("Not adding group " + group.getName() + " (" + groupId + ") to Discord: group has a password.");
            return;
        }

        VoicechatConnection connection = event.getConnection();
        if (connection == null) {
            platform.debug("someone created " + groupId + " (" + group.getName() + ")");
            return;
        }
        ServerPlayer player = connection.getPlayer();

        // Track the owner of the group
        groupOwnerMap.put(groupId, player.getUuid());

        if (!Core.bots.isEmpty()) {
            DiscordBot found = findAvailableBot();
            if (found == null) {
                platform.warn("No available Discord bots to assign to group " + group.getName() + " (" + groupId + ")! All bots are started or already assigned.\n" +
                    "Bot status: " + Core.bots.stream().map(b -> "started=" + b.isStarted() + ", assigned=" + groupBotMap.containsValue(b)).toList());
                // Send a message to the player who created the group
                Component message = Component.red("[Discord] ")
                    .append(Component.white("Unable to create Discord voice channel for group '"))
                    .append(Component.yellow(group.getName()))
                    .append(Component.white("'. No Discord bots are available."));
                platform.sendMessage(player, message);
                return;
            }
            final DiscordBot bot = found;
            pendingGroupCreations.put(groupId, bot);
            new Thread(() -> {
                if (bot.logIn()) {
                    bot.createDiscordVoiceChannelAsync(group.getName(), discordChannelId -> {
                        if (discordChannelId == null) {
                            platform.error("Failed to create Discord voice channel for group " + group.getName() + " (" + groupId + ")");
                            pendingGroupCreations.remove(groupId);
                            return;
                        }

                        bot.getLifecycleLock().lock();
                        try {
                            boolean started = startVoiceWithRetry(bot);
                            if (!started) {
                                platform.error("Failed to start voice connection for group " + group.getName() + " (" + groupId + "); deleting the Discord channel.");
                                pendingGroupCreations.remove(groupId);
                                bot.deleteDiscordVoiceChannelAsync();
                                bot.stop();
                                platform.sendMessage(player, Component.red("[Discord] "),
                                    Component.white("Failed to connect the Discord bot for group '"),
                                    Component.yellow(group.getName()),
                                    Component.white("'. Please try recreating the group."));
                                return;
                            }

                            pendingGroupCreations.remove(groupId);
                            synchronized (removedBeforeCreation) {
                                if (removedBeforeCreation.contains(groupId)) {
                                    platform.debug("Group " + groupId + " (" + group.getName() + ") was removed before Discord channel creation finished. Deleting channel.");
                                    bot.deleteDiscordVoiceChannelAsync();
                                    removedBeforeCreation.remove(groupId);
                                    bot.stop();
                                    return;
                                }
                            }

                            bot.startDiscordAudioThread(groupId);
                            groupBotMap.put(groupId, bot);
                            platform.debug("Linked groupId " + groupId + " (" + group.getName() + ") to bot (discordChannelId=" + discordChannelId + ")");
                        } finally {
                            bot.getLifecycleLock().unlock();
                        }

                        platform.debug(player.getUuid() + " (" + platform.getName(player) + ") created " + groupId + " (" + group.getName() + ")");

                        List<ServerPlayer> players = getPlayers(group);
                        players.add(player);
                        handlePlayerJoin(group, player, connection, bot, players.size());
                        processQueuedJoinEvents(groupId, group);
                    });
                } else {
                    platform.error("Failed to login to Discord for group " + group.getName() + " (" + groupId + ")");
                    pendingGroupCreations.remove(groupId);
                }
            }, "voicechat-discord: Bot AutoStart for Group").start();
        } else {
            platform.warn("No available Discord bots to assign to group " + group.getName() + " (" + groupId + ")");
            // Send a message to the player who created the group
            Component message = Component.red("[Discord] ")
                .append(Component.white("Unable to create Discord voice channel for group '"))
                .append(Component.yellow(group.getName()))
                .append(Component.white("'. No Discord bots are configured."));
            platform.sendMessage(player, message);
        }
    }

    @SuppressWarnings("DataFlowIssue")
    public static void onGroupRemoved(RemoveGroupEvent event) {
        Group group = event.getGroup();
        UUID groupId = group.getId();
        boolean permanent = isPermanentGroup(groupId);

        platform.debug("onGroupRemoved: Group removed: " + groupId + ", " + group.getName() + ")");

        // If group is still pending creation, mark for deletion after creation
        if (pendingGroupCreations.containsKey(groupId)) {
            synchronized (removedBeforeCreation) {
                removedBeforeCreation.add(groupId);
            }
            if (permanent) {
                platform.debug("onGroupRemoved: Permanent group " + groupId + " is pending startup bridge creation. It will be stopped without deleting its Discord channel.");
            } else {
                platform.debug("onGroupRemoved: Group " + groupId + " is pending Discord channel creation. Will delete after creation.");
            }
        }

        // Clean up all Discord users in the group's VC as if they left,
        // and remove the group->bot association
        DiscordBot bot = groupBotMap.get(groupId);
        unlinkGroupFromDiscord(groupId);
        if (bot != null) {
            platform.debug("onGroupRemoved: Stopping Discord bot for group: " + group.getName() + ")");
            final DiscordBot stoppingBot = bot;
            new Thread(() -> {
                stoppingBot.getLifecycleLock().lock();
                try {
                    if (permanent) {
                        stoppingBot.disconnect();
                        stoppingBot.stop(false);
                    } else {
                        stoppingBot.stop();
                    }
                } catch (Throwable t) {
                    platform.error("onGroupRemoved: Failed to stop Discord bot for group: " + group.getName() + " (" + groupId + ")", t);
                } finally {
                    stoppingBot.getLifecycleLock().unlock();
                }
            }, "voicechat-discord: Group Remove Stop").start();
            platform.debug("onGroupRemoved: Stopping Discord bot for group: " + group.getName() + ") in background");
        }

        groupPlayerMap.remove(groupId);
        groupOwnerMap.remove(groupId);
        if (permanent) {
            permanentGroupId = null;
        }
    }

    private static void removePlayerFromGroup(UUID groupId, UUID playerUuid, String playerName) {
        List<ServerPlayer> players = groupPlayerMap.get(groupId);
        if (players != null) {
            players.removeIf(p -> p.getUuid().equals(playerUuid));
        }

        // Remove all StaticAudioChannels for this player if present
        Map<UUID, Map<Long, StaticAudioChannel>> channels = groupAudioChannels.get(groupId);
        if (channels != null) {
            channels.remove(playerUuid);
        }

        if (players != null && !players.isEmpty()) {
            DiscordBot bot = groupBotMap.get(groupId);
            if (bot != null) {
                String leaveMsg = "[<t:" + (System.currentTimeMillis() / 1000) + ":t>] **" + playerName + "** left the group. (" + players.size() + (players.size() == 1 ? " Player" : " Players") + ")";
                bot.sendDiscordTextMessageAsync(leaveMsg, true);
            }
        }

        syncPermanentGroupVoiceConnection(groupId);
    }

    public static void handleMinecraftPlayerLeave(UUID playerUuid) {
        for (Map.Entry<UUID, List<ServerPlayer>> entry : groupPlayerMap.entrySet()) {
            UUID groupId = entry.getKey();
            String playerName = null;
            List<ServerPlayer> players = entry.getValue();
            for (ServerPlayer p : players) {
                if (p.getUuid().equals(playerUuid)) {
                    playerName = platform.getName(p);
                    break;
                }
            }
            if (playerName != null) {
                removePlayerFromGroup(groupId, playerUuid, playerName);
                break;
            }
        }
    }
}
