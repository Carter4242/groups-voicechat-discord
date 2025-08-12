package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import de.maxhenkel.voicechat.api.packets.ConvertablePacket;

import java.util.UUID;

import static dev.amsam0.voicechatdiscord.Core.platform;

public final class DiscordBot {
    // Track the last set of talking Discord users shown to the group
    private java.util.Set<String> lastSentTalkingUsers = java.util.Collections.emptySet();
    private long lastSentTime = 0L;
    /**
     * Thread that polls for Discord audio and sends it to group members.
     */
    private Thread discordAudioThread;
    private volatile boolean running = false;
    private volatile boolean freed = false;
    // Discord connection and bridging logic
    private final long categoryId;
    private final long ptr;

    // Store the Discord voice channel ID for this group
    private volatile Long discordChannelId = null;

    public Long getDiscordChannelId() {
        return discordChannelId;
    }

    private native long _new(String token, long categoryId);

    public DiscordBot(String token, long categoryId) {
        this.categoryId = categoryId;
        ptr = _new(token, categoryId);
        this.discordChannelId = null;
    }

    /**
     * Asynchronously creates a Discord voice channel for this group. Calls the callback with the channel ID (or null on failure).
     * @param groupName The name for the new Discord voice channel
     * @param callback Callback to receive the channel ID (or null)
     */
    public void createDiscordVoiceChannelAsync(String groupName, java.util.function.Consumer<Long> callback) {
        if (freed) {
            platform.warn("Attempted to create Discord channel after bot was freed");
            callback.accept(null);
            return;
        }
        new Thread(() -> {
            try {
                String initialName = "[1 Player] " + groupName;
                long channelId = _createDiscordVoiceChannel(ptr, initialName);
                if (channelId != 0L) {
                    this.discordChannelId = channelId;
                    platform.info("Created Discord voice channel '" + initialName + "' with ID " + channelId);
                    callback.accept(channelId);
                } else {
                    platform.error("Failed to create Discord voice channel for group '" + groupName + "'");
                    callback.accept(null);
                }
            } catch (Throwable t) {
                platform.error("Exception while creating Discord voice channel for group '" + groupName + "' (categoryId=" + categoryId + ")", t);
                callback.accept(null);
            }
        }, "DiscordChannelCreateThread").start();
    }

    /**
     * Deletes the Discord voice channel associated with this bot/group.
     */
    /**
     * Asynchronously deletes the Discord voice channel associated with this bot/group.
     */
    public void deleteDiscordVoiceChannelAsync() {
        deleteDiscordVoiceChannelAsync(null);
    }

    /**
     * Asynchronously deletes the Discord voice channel associated with this bot/group, then runs the callback if provided.
     */
    public void deleteDiscordVoiceChannelAsync(Runnable afterDelete) {
        if (freed) {
            if (afterDelete != null) afterDelete.run();
            return;
        }
        final Long channelIdToDelete = discordChannelId;
        if (channelIdToDelete == null) {
            platform.warn("No Discord channel to delete for categoryId=" + categoryId);
            if (afterDelete != null) afterDelete.run();
            return;
        }
        platform.info("Deleting Discord voice channel with ID " + channelIdToDelete);
        new Thread(() -> {
            try {
                _deleteDiscordVoiceChannel(ptr);
                platform.info("Deleted Discord voice channel with ID " + channelIdToDelete + " for categoryId=" + categoryId);
            } catch (Throwable t) {
                platform.error("Exception while deleting Discord voice channel with ID " + channelIdToDelete + " (categoryId=" + categoryId + ")", t);
            } finally {
                // Only clear if we deleted the same channel
                if (discordChannelId != null && discordChannelId.equals(channelIdToDelete)) {
                    discordChannelId = null;
                }
                if (afterDelete != null) afterDelete.run();
            }
        }, "DiscordChannelDeleteThread").start();
    }

    /**
     * Asynchronously updates the Discord voice channel name to reflect the current player count and group name.
     * @param playerCount Number of players in the group
     * @param groupName The group name
     */
    public void updateDiscordVoiceChannelNameAsync(int playerCount, String groupName) {
        if (freed) {
            platform.warn("Attempted to update Discord channel name after bot was freed");
            return;
        }
        final Long channelIdToUpdate = discordChannelId;
        if (channelIdToUpdate == null) {
            platform.warn("No Discord channel to update for categoryId=" + categoryId);
            return;
        }
        String playerWord = (playerCount == 1) ? "Player" : "Players";
        String newName = "[" + playerCount + " " + playerWord + "] " + groupName;
        new Thread(() -> {
            try {
                _updateDiscordVoiceChannelName(ptr, newName);
                platform.info("Updated Discord voice channel name to '" + newName + "' for channelId=" + channelIdToUpdate);
            } catch (Throwable t) {
                platform.error("Exception while updating Discord voice channel name for channelId=" + channelIdToUpdate + " (categoryId=" + categoryId + ")", t);
            }
        }, "DiscordChannelUpdateNameThread").start();
    }

    // Native method for updating channel name
    private static native void _updateDiscordVoiceChannelName(long ptr, String newName);

    // Native methods for channel management
    private static native long _createDiscordVoiceChannel(long ptr, String groupName);
    private static native void _deleteDiscordVoiceChannel(long ptr);

    /**
     * Asynchronously sends a text message to the Discord voice channel's text chat.
     * @param message The message to send
     */
    public void sendDiscordTextMessageAsync(String message) {
        if (freed) {
            platform.warn("Attempted to send Discord text message after bot was freed");
            return;
        }
        final Long channelIdToSend = discordChannelId;
        if (channelIdToSend == null) {
            platform.warn("No Discord channel to send text message for categoryId=" + categoryId);
            return;
        }
        new Thread(() -> {
            try {
                _sendDiscordTextMessage(ptr, message);
            } catch (Throwable t) {
                platform.error("Exception while sending Discord text message for channelId=" + channelIdToSend + " (categoryId=" + categoryId + ")", t);
            }
        }, "DiscordChannelSendTextThread").start();
    }

    // Native method for sending a text message
    private static native void _sendDiscordTextMessage(long ptr, String message);

    /**
     * Starts the background thread for Discord audio bridging.
     */
    public void startDiscordAudioThread(UUID groupId) {
        if (freed) {
            platform.warn("Attempted to start audio thread after bot was freed");
            return;
        }
        running = true;
        discordAudioThread = new Thread(() -> {
            while (running && !freed) {
                try {
                    if (freed) break;
                    Object result = _blockForSpeakingBufferOpusData(ptr);
                    if (freed) break;

                    if (result instanceof byte[][][] userPackets && userPackets.length > 0) {
                        sendDiscordAudioToGroup(groupId, userPackets);
                    } else {
                        sendDiscordAudioToGroup(groupId, new byte[0][][]);
                    }
                } catch (Throwable t) {
                    platform.error("Error in Discord audio thread for bot (categoryId=" + categoryId + ")", t);
                }
            }
        }, "DiscordAudioBridgeThread");
        discordAudioThread.setDaemon(true);
        discordAudioThread.start();
    }

    
    /**
     * Send a list of Discord Opus audio packets (with usernames) to all group members in the specified group.
     * @param groupId The group to send audio to
     * @param userPackets Array of [usernameBytes, opusBytes] for each packet
     */
    public void sendDiscordAudioToGroup(UUID groupId, byte[][][] userPackets) {
        // Collect currently talking Discord usernames
        java.util.Set<String> talkingUsersSet = new java.util.HashSet<>();
        for (int i = 0; i < userPackets.length; i++) {
            byte[][] tuple = userPackets[i];
            if (tuple == null || tuple.length != 2) continue;
            String username = new String(tuple[0]);
            byte[] opusData = tuple[1];
            if (opusData != null && opusData.length > 0 && username != null && !username.isEmpty()) {
                talkingUsersSet.add(username);
            }
        }
        // Sort usernames alphabetically for display
        java.util.List<String> talkingUsers = new java.util.ArrayList<>(talkingUsersSet);
        java.util.Collections.sort(talkingUsers, String.CASE_INSENSITIVE_ORDER);

        // Only update action bar if the set of talking users changed, or every 2.0s
        long now = System.currentTimeMillis();
        boolean usersChanged = !new java.util.HashSet<>(talkingUsers).equals(lastSentTalkingUsers);
        boolean timeout = (now - lastSentTime > 2000);
        if ((usersChanged && talkingUsers.isEmpty()) || (usersChanged || (timeout && !talkingUsers.isEmpty()))) {
            lastSentTalkingUsers = new java.util.HashSet<>(talkingUsers);
            lastSentTime = now;
            Component[] msg;
            if (talkingUsers.isEmpty()) {
                msg = new Component[] { Component.blue("") };
            } else if (talkingUsers.size() == 1) {
                msg = new Component[] {
                    Component.gold(talkingUsers.get(0)),
                    Component.green(" is talking")
                };
            } else {
                java.util.List<Component> msgList = new java.util.ArrayList<>();
                for (int i = 0; i < talkingUsers.size(); i++) {
                    if (i > 0) msgList.add(Component.white(", "));
                    msgList.add(Component.gold(talkingUsers.get(i)));
                }
                msgList.add(Component.green(" are talking"));
                msg = msgList.toArray(new Component[0]);
            }

            var players = GroupManager.groupPlayerMap.get(groupId);
            if (players != null) {
                for (var serverPlayer : players) {
                    platform.sendActionBar(serverPlayer, msg);
                }
            }
        }

        if (userPackets.length == 0) {
            return;
        }
        
        var groupChannels = GroupManager.groupAudioChannels.get(groupId);
        for (var entry : groupChannels.entrySet()) {
            UUID playerId = entry.getKey();
            var channelList = entry.getValue();
            if (channelList == null) {
                channelList = new java.util.ArrayList<>();
                groupChannels.put(playerId, channelList);
            }

            for (int i = 0; i < userPackets.length; i++) {
                byte[][] tuple = userPackets[i];
                if (tuple == null || tuple.length != 2) continue;
                byte[] opusData = tuple[1];

                if (i >= channelList.size() || channelList.get(i) == null || channelList.get(i).isClosed()) {
                    // Only fetch these if we need to create a new channel
                    var connection = Core.api.getConnectionOf(playerId);
                    var player = connection != null ? connection.getPlayer() : null;
                    var group = Core.api.getGroup(groupId);
                    var level = player != null ? player.getServerLevel() : null;
                    if (group != null && level != null && connection != null) {
                        // Use a random UUID for the channelId instead of groupId
                        var randomChannelId = UUID.randomUUID();
                        var newChannel = Core.api.createStaticAudioChannel(randomChannelId, level, connection);
                        if (newChannel != null) {
                            if (i < channelList.size()) {
                                channelList.set(i, newChannel);
                            } else {
                                channelList.add(newChannel);
                            }
                        } else {
                            platform.error("Failed to create StaticAudioChannel for player " + playerId + " in group " + groupId + " for packet index " + i);
                        }
                    } else {
                        platform.error("Cannot create StaticAudioChannel: missing group, level, or connection for player " + playerId);
                        continue;
                    }
                }
                var channel = channelList.get(i);
                if (channel != null && !channel.isClosed() && opusData != null && opusData.length > 0) {
                    channel.send(opusData);
                }
            }
        }
    }

    /**
     * Stops the background thread for Discord audio bridging.
     */
    private void stopDiscordAudioThread() {
        running = false;
        if (discordAudioThread != null) {
            discordAudioThread.interrupt();
            try {
                discordAudioThread.join(100);
            } catch (InterruptedException ignored) {}
            discordAudioThread = null;
        }
    }

    private native boolean _isStarted(long ptr);

    public boolean isStarted() {
        return _isStarted(ptr);
    }

    private native void _logIn(long ptr) throws Throwable;

    public boolean logIn() {
        try {
            _logIn(ptr);
            platform.debug("Logged into the bot (categoryId=" + categoryId + ")");
            return true;
        } catch (Throwable e) {
            platform.error("Failed to login to the bot (categoryId=" + categoryId + ")", e);
            return false;
        }
    }

    private native String _start(long ptr) throws Throwable;

    public void start() {
        try {
            String vcName = _start(ptr);
            platform.info("Started voice chat for group in channel " + vcName + " with bot (categoryId=" + categoryId + ")");
        } catch (Throwable e) {
            platform.error("Failed to start voice connection for bot (categoryId=" + categoryId + ")", e);
        }
    }

    private native void _stop(long ptr) throws Throwable;

    public void stop() {
        stop(true);
    }

    /**
     * Stops the background thread for Discord audio bridging and optionally deletes the Discord voice channel.
     * @param deleteChannel If true, deletes the Discord voice channel; if false, leaves it intact.
     */
    public void stop(boolean deleteChannel) {
        try {
            stopDiscordAudioThread();
            if (deleteChannel) {
                deleteDiscordVoiceChannelAsync(() -> {
                    try {
                        _stop(ptr);
                    } catch (Throwable e) {
                        platform.error("Failed to stop bot (categoryId=" + categoryId + ")", e);
                    }
                    platform.info("DiscordBot.stop finished for categoryId=" + categoryId);
                });
            } else {
                try {
                    _stop(ptr);
                } catch (Throwable e) {
                    platform.error("Failed to stop bot (categoryId=" + categoryId + ")", e);
                }
                platform.info("DiscordBot.stop finished for categoryId=" + categoryId);
            }
        } catch (Throwable e) {
            platform.error("Failed to stop bot (categoryId=" + categoryId + ")", e);
            platform.info("DiscordBot.stop finished for categoryId=" + categoryId);
        }
    }

    private native void _free(long ptr);

    /**
     * Safety: the class should be discarded after calling
     */
    public void free() {
        freed = true;
        stopDiscordAudioThread();
        _free(ptr);
    }

    private native void _addAudioToHearingBuffer(long ptr, byte[] groupIdBytes, byte[] rawOpusData, long sequenceNumber);

    /**
     * Handles a MicrophonePacketEvent for bridging Minecraft group audio to Discord (including solo group members).
     */
    public static void handleGroupMicrophonePacketEvent(de.maxhenkel.voicechat.api.events.MicrophonePacketEvent event) {
        try {
            var senderConn = event.getSenderConnection();
            if (senderConn == null) {
                return;
            }
            var group = senderConn.getGroup();
            if (group == null) {
                return;
            }
            var bot = GroupManager.groupBotMap.get(group.getId());
            if (bot == null) {
                return;
            }

            Long channelId = bot.getDiscordChannelId();
            boolean hasUsers = false;
            if (channelId != null) {
                for (var entry : GroupManager.discordUserChannelMap.entrySet()) {
                    if (channelId.equals(entry.getValue())) {
                        hasUsers = true;
                        break;
                    }
                }
            }
            if (!hasUsers) {
                return;
            }

            var sender = senderConn.getPlayer();
            if (sender == null) {
                return;
            }
            var packet = event.getPacket();
            if (!(packet instanceof ConvertablePacket convertable)) {
                platform.warn("[handleGroupMicrophonePacketEvent] Packet is not convertable to StaticSoundPacket!");
                return;
            }
            bot.handlePacket(convertable.staticSoundPacketBuilder().build(), sender);
        } catch (Throwable t) {
            platform.error("[handleGroupMicrophonePacketEvent] Exception occurred", t);
        }
    }

    /**
     * Receives a group audio packet from Minecraft and sends it to Discord.
     */
    public void handlePacket(StaticSoundPacket packet, ServerPlayer player) {
        if (freed) {
            platform.warn("handlePacket called after bot was freed");
            return;
        }
        if (player == null) {
            platform.warn("handlePacket called with null player");
            return;
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            platform.warn("StaticSoundPacket missing playerId");
            return;
        }
        byte[] playerIdBytes = uuidToBytes(playerId);
        byte[] opusData = packet.getOpusEncodedData();
        long sequenceNumber = packet.getSequenceNumber();
        _addAudioToHearingBuffer(ptr, playerIdBytes, opusData, sequenceNumber);
    }

    /**
     * Converts a UUID to a 16-byte array.
     */
    private static byte[] uuidToBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) bytes[i] = (byte) (msb >>> (8 * (7 - i)));
        for (int i = 0; i < 8; i++) bytes[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        return bytes;
    }
    
    private native Object _blockForSpeakingBufferOpusData(long ptr);

    /**
     * Disconnects the bot from the Discord voice channel, but does NOT delete the channel.
     */
    public void disconnect() {
        try {
            _disconnect(ptr);
            platform.info("Disconnected Discord bot (categoryId=" + categoryId + ") from voice channel (without deleting). ");
        } catch (Throwable e) {
            platform.error("Failed to disconnect Discord bot (categoryId=" + categoryId + ")", e);
        }
    }

    // Native method for disconnecting from the voice channel without deleting it
    private native void _disconnect(long ptr);

    /**
     * Called from Rust when a Discord user's voice state changes (join/leave VC).
     * @param discordUserId The Discord user ID (as a long)
     * @param username The Discord username
     * @param channelId The Discord channel ID (as a long), or 0 if leaving
     * @param joined True if the user joined, false if left
     */
    public void onDiscordUserVoiceState(long discordUserId, String username, long channelId, boolean joined) {
        if (Core.botUserIds != null && Core.botUserIds.contains(discordUserId)) {
            return;
        }

        // If user is switching channels, show both leave and join messages
        Long previousChannelId = GroupManager.discordUserChannelMap.get(discordUserId);
        boolean isSwitching = joined && previousChannelId != null && previousChannelId != 0L && previousChannelId != channelId;

        // If switching, first send leave message for old channel
        if (isSwitching) {
            // Remove from old channel
            Long oldChannelId = GroupManager.discordUserChannelMap.remove(discordUserId);
            GroupManager.discordUserNameMap.remove(discordUserId);
            if (oldChannelId != null && oldChannelId != 0L) {
                // Find the group for the old channel
                UUID oldGroupId = null;
                for (var entry : GroupManager.groupBotMap.entrySet()) {
                    DiscordBot bot = entry.getValue();
                    if (bot != null && bot.discordChannelId != null && bot.discordChannelId.equals(oldChannelId)) {
                        oldGroupId = entry.getKey();
                        break;
                    }
                }
                if (oldGroupId != null) {
                    var oldPlayers = GroupManager.groupPlayerMap.get(oldGroupId);
                    if (oldPlayers != null && !oldPlayers.isEmpty()) {
                        Component prefix = Component.blue("[Discord] ");
                        Component name = Component.gold(username);
                        Component action = Component.red(" left the Discord voice channel.");
                        for (var player : oldPlayers) {
                            platform.sendMessage(player, prefix, name, action);
                        }
                    }
                }
            }
        }

        platform.info("[DiscordBot] User " + (joined ? "joined" : "left") + " Discord VC: " + username + " (ID: " + discordUserId + ") in channel " + channelId);

        Long groupChannelId = channelId;
        if (joined) {
            // If already in the correct channel, skip (avoid duplicate join event)
            Long existing = GroupManager.discordUserChannelMap.get(discordUserId);
            if (existing != null && existing.equals(channelId)) {
                platform.debug("[DiscordBot] User " + discordUserId + " already in channel " + channelId + ", skipping join event.");
                return;
            }
            GroupManager.discordUserChannelMap.put(discordUserId, channelId);
            GroupManager.discordUserNameMap.put(discordUserId, username);
        } else {
            // On leave, look up the last channel they were in
            groupChannelId = GroupManager.discordUserChannelMap.remove(discordUserId);
            GroupManager.discordUserNameMap.remove(discordUserId);
            if (groupChannelId == null || groupChannelId == 0L) {
                platform.info("[DiscordBot] No previous channel found for user " + discordUserId + ", cannot send leave message.");
                return;
            }
        }

        // Find the group whose Discord channel ID matches
        UUID foundGroupId = null;
        for (var entry : GroupManager.groupBotMap.entrySet()) {
            DiscordBot bot = entry.getValue();
            if (bot != null && bot.discordChannelId != null && bot.discordChannelId.equals(groupChannelId)) {
                foundGroupId = entry.getKey();
                break;
            }
        }
        if (foundGroupId == null) {
            platform.info("[DiscordBot] No group found for Discord channel ID " + groupChannelId);
            return;
        }

        // Get all players in the group and send them a message
        var players = GroupManager.groupPlayerMap.get(foundGroupId);
        if (players == null || players.isEmpty()) {
            platform.info("[DiscordBot] No players in group " + foundGroupId + " for Discord channel ID " + groupChannelId);
            return;
        }
        // Compose a pretty message: [Discord] <username> joined/left the Discord voice channel.
        Component prefix = Component.blue("[Discord] ");
        Component name = Component.gold(username);
        Component action = joined
            ? Component.green(" joined the Discord voice channel.")
            : Component.red(" left the Discord voice channel.");
        for (var player : players) {
            platform.sendMessage(player, prefix, name, action);
        }
    }
}