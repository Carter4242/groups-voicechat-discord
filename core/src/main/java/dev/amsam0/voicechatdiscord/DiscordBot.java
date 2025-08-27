package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import de.maxhenkel.voicechat.api.packets.ConvertablePacket;

import java.util.UUID;

import static dev.amsam0.voicechatdiscord.Core.platform;

public final class DiscordBot {
    // Map of Discord user IDs to their category IDs (as String)
    public static final java.util.Map<Long, String> discordUserCategoryMap = new java.util.HashMap<>();
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
    private volatile long ptr;

    // Store the Discord voice channel ID for this group
    private volatile Long discordChannelId = null;

    // Track the last Discord message for join/leave consolidation
    private volatile Long lastDiscordMessageId = null;
    public volatile boolean lastMessageWasJoinLeave = false;
    private volatile int lastMessageEditCount = 0;

    // Formats Discord emotes in a message to :name: for Minecraft display.
    private static final java.util.regex.Pattern EMOTE_PATTERN = java.util.regex.Pattern.compile("<a?:([A-Za-z0-9_]+):[0-9]+>");


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
        if (freed || ptr == 0) {
            platform.warn("Attempted to create Discord channel after bot was freed or ptr was invalid (vcid=null, categoryId=" + categoryId + ")");
            callback.accept(null);
            return;
        }
        new Thread(() -> {
            try {
                String initialName = groupName;
                long channelId = _createDiscordVoiceChannel(ptr, initialName);
                if (channelId != 0L) {
                    this.discordChannelId = channelId;
                    platform.info("Created Discord voice channel '" + initialName + "' with vcid=" + channelId + " (categoryId=" + categoryId + ")");
                    callback.accept(channelId);
                } else {
                    platform.error("Failed to create Discord voice channel for group '" + groupName + "' (vcid=null, categoryId=" + categoryId + "). Check Rust logs for details.");
                    callback.accept(null);
                }
            } catch (Throwable t) {
                platform.error("Exception while creating Discord voice channel for group '" + groupName + "' (vcid=null, categoryId=" + categoryId + ")", t);
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
    private Thread deleteThread;

    public void deleteDiscordVoiceChannelAsync(Runnable afterDelete) {
        if (freed || ptr == 0) {
            if (afterDelete != null) afterDelete.run();
            return;
        }
        final Long channelIdToDelete = discordChannelId;
        if (channelIdToDelete == null) {
            platform.warn("No Discord channel to delete for vcid=null (categoryId=" + categoryId + ")");
            if (afterDelete != null) afterDelete.run();
            return;
        }
        platform.info("Deleting Discord voice channel with vcid=" + channelIdToDelete + " (categoryId=" + categoryId + ")");
        deleteThread = new Thread(() -> {
            try {
                _deleteDiscordVoiceChannel(ptr);
                platform.info("Deleted Discord voice channel with vcid=" + channelIdToDelete + " (categoryId=" + categoryId + ")");
            } catch (Throwable t) {
                platform.error("Exception while deleting Discord voice channel with vcid=" + channelIdToDelete + " (categoryId=" + categoryId + ")", t);
            } finally {
                // Only clear if we deleted the same channel
                if (discordChannelId != null && discordChannelId.equals(channelIdToDelete)) {
                    discordChannelId = null;
                    lastDiscordMessageId = null;
                    lastMessageWasJoinLeave = false;
                    lastMessageEditCount = 0;
                }
                if (afterDelete != null) afterDelete.run();
            }
        }, "DiscordChannelDeleteThread");
        deleteThread.start();
    }

    /**
     * Waits for the delete thread to finish, if it exists.
     */
    public void waitForDeleteThread() {
        if (deleteThread != null) {
            try {
                deleteThread.join(8000); // Wait up to 8 seconds
            } catch (InterruptedException ignored) {}
            deleteThread = null;
        }
    }

    /**
     * Asynchronously updates the Discord voice channel name to reflect the current player count and group name.
     * @param playerCount Number of players in the group
     * @param groupName The group name
     */
    public void updateDiscordVoiceChannelNameAsync(int playerCount, String groupName) {
        if (freed || ptr == 0) {
            platform.warn("Attempted to update Discord channel name after bot was freed or ptr was invalid");
            return;
        }
        final Long channelIdToUpdate = discordChannelId;
        if (channelIdToUpdate == null) {
            platform.warn("No Discord channel to update for vcid=null (categoryId=" + categoryId + ")");
            return;
        }
        String playerWord = (playerCount == 1) ? "Player" : "Players";
        String newName = "[" + playerCount + " " + playerWord + "] " + groupName;
        new Thread(() -> {
            try {
                _updateDiscordVoiceChannelName(ptr, newName);
                platform.info("Updated Discord voice channel name to '" + newName + "' for vcid=" + channelIdToUpdate + " (categoryId=" + categoryId + ")");
            } catch (Throwable t) {
                platform.error("Exception while updating Discord voice channel name for vcid=" + channelIdToUpdate + " (categoryId=" + categoryId + ")", t);
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
        sendDiscordTextMessageAsync(message, false);
    }

    /**
     * Asynchronously sends a text message to the Discord voice channel's text chat.
     * @param message The message to send
     * @param isJoinLeaveMessage Whether this is a join/leave message that might be consolidated
     */
    public void sendDiscordTextMessageAsync(String message, boolean isJoinLeaveMessage) {
        if (freed || ptr == 0) {
            platform.warn("Attempted to send Discord text message after bot was freed or ptr was invalid (vcid=" + discordChannelId + ")");
            return;
        }
        final Long channelIdToSend = discordChannelId;
        if (channelIdToSend == null) {
            platform.warn("No Discord channel to send text message for vcid=null (categoryId=" + categoryId + ")");
            return;
        }

        new Thread(() -> {
            try {
                synchronized (this) {
                    if (isJoinLeaveMessage && shouldEditLastMessage()) {
                        editLastDiscordMessageAppend("\n" + message);
                    } else {
                        long messageId = _sendDiscordTextMessageWithId(ptr, message);
                        
                        if (messageId != 0) {
                            lastDiscordMessageId = messageId;
                            lastMessageWasJoinLeave = isJoinLeaveMessage;
                            lastMessageEditCount = 0;
                        }
                    }
                }
            } catch (Throwable t) {
                platform.error("Exception while sending Discord text message for vcid=" + channelIdToSend + " (categoryId=" + categoryId + "). Check Rust logs for details.", t);
            }
        }, "DiscordChannelSendTextThread").start();
    }

    /**
     * Checks if the last message should be edited instead of sending a new message.
     * @return true if the last message was a join/leave message sent recently and hasn't been edited too many times
     */
    private boolean shouldEditLastMessage() {
        return (lastDiscordMessageId != null && lastMessageWasJoinLeave && lastMessageEditCount < 9);
    }

    /**
     * Edits the last Discord message by appending new content.
     * @param contentToAppend The content to append to the existing message
     */
    private void editLastDiscordMessageAppend(String contentToAppend) {
        if (lastDiscordMessageId == null) {
            return;
        }
        try {
            _editDiscordTextMessageAppend(ptr, lastDiscordMessageId, contentToAppend);
            lastMessageEditCount++;
        } catch (Throwable t) {
            platform.error("Exception while appending to Discord text message (messageId=" + lastDiscordMessageId + ", vcid=" + discordChannelId + ")", t);
        }
    }

    // Native method for appending to a text message
    private static native void _editDiscordTextMessageAppend(long ptr, long messageId, String contentToAppend);

    // Native method for sending a text message and returning the message ID
    private static native long _sendDiscordTextMessageWithId(long ptr, String message);

    /**
     * Starts the background thread for Discord audio bridging.
     */
    public void startDiscordAudioThread(UUID groupId) {
        if (freed || ptr == 0) {
            platform.warn("Attempted to start audio thread after bot was freed or ptr was invalid (vcid=" + discordChannelId + ")");
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
                    platform.error("Error in Discord audio thread for bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + ")", t);
                }
            }
        }, "DiscordAudioBridgeThread");
        discordAudioThread.setDaemon(true);
        discordAudioThread.start();
    }

    
    /**
     * Send a list of Discord Opus audio packets (with usernames and user IDs) to all group members in the specified group.
     * @param groupId The group to send audio to
     * @param userPackets Array of [usernameBytes, userIdBytes, opusBytes] for each packet
     */
    public void sendDiscordAudioToGroup(UUID groupId, byte[][][] userPackets) {
        // Collect currently talking Discord usernames
        java.util.Set<String> talkingUsersSet = new java.util.HashSet<>();
        for (int i = 0; i < userPackets.length; i++) {
            byte[][] tuple = userPackets[i];
            if (tuple == null || tuple.length != 3) continue;
            String username = new String(tuple[0]);
            byte[] opusData = tuple[2];
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
        if (groupChannels == null) return;
        for (int i = 0; i < userPackets.length; i++) {
            byte[][] tuple = userPackets[i];
            if (tuple == null || tuple.length != 3) continue;
            String username = new String(tuple[0]);
            // Extract Discord user ID from bytes using ByteBuffer
            long discordUserId = 0;
            if (tuple[1].length >= 8) {
                discordUserId = java.nio.ByteBuffer.wrap(tuple[1]).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
            }
            byte[] opusData = tuple[2];
            if (opusData == null || opusData.length == 0 || username == null || username.isEmpty()) continue;
            for (var entry : groupChannels.entrySet()) {
                var playerId = entry.getKey();
                var playerChannels = entry.getValue();
                var channel = playerChannels != null ? playerChannels.get(discordUserId) : null;
                if (channel == null) {
                    // Create channel on the fly
                    var connection = Core.api.getConnectionOf(playerId);
                    var group = Core.api.getGroup(groupId);
                    var player = connection != null ? connection.getPlayer() : null;
                    var level = player != null ? player.getServerLevel() : null;
                    if (group != null && level != null && connection != null) {
                        var randomChannelId = UUID.randomUUID();
                        var newChannel = Core.api.createStaticAudioChannel(randomChannelId, level, connection);
                        if (newChannel != null) {
                            String categoryId = discordUserCategoryMap.get(discordUserId);
                            if (categoryId != null) {
                                newChannel.setCategory(categoryId);
                            }
                            playerChannels.put(discordUserId, newChannel);
                            channel = newChannel;
                            platform.info("[sendDiscordAudioToGroup] Created StaticAudioChannel on the fly for player " + playerId + ", Discord user '" + username + "' (ID: " + discordUserId + ") in group " + groupId + " (vcid=" + discordChannelId + ")");
                        } else {
                            platform.error("[sendDiscordAudioToGroup] Failed to create StaticAudioChannel for player " + playerId + ", Discord user '" + username + "' (ID: " + discordUserId + ") in group " + groupId + " (vcid=" + discordChannelId + ")");
                        }
                    } else {
                        platform.error("[sendDiscordAudioToGroup] Cannot create StaticAudioChannel: missing group, level, or connection for player " + playerId + " (vcid=" + discordChannelId + ")");
                        continue;
                    }
                }
                if (channel != null && !channel.isClosed()) {
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
        if (freed || ptr == 0) {
            platform.warn("Attempted to check isStarted after bot was freed or ptr was invalid");
            return false;
        }
        return _isStarted(ptr);
    }

    private native void _logIn(long ptr) throws Throwable;

    public boolean logIn() {
        if (freed || ptr == 0) {
            platform.warn("Attempted to logIn after bot was freed or ptr was invalid");
            return false;
        }
        try {
            _logIn(ptr);
            platform.debug("Logged into the bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + ")");
            return true;
        } catch (Throwable e) {
            platform.error("Failed to login to the bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + "). Check Rust logs for details.", e);
            return false;
        }
    }

    private native String _start(long ptr) throws Throwable;

    public void start() {
        if (freed || ptr == 0) {
            platform.warn("Attempted to start after bot was freed or ptr was invalid");
            return;
        }
        try {
            String vcName = _start(ptr);
            if (vcName == null || vcName.equals("<panic>") || vcName.equals("<error>") || vcName.equals("<null pointer error>")) {
                platform.error("Failed to start voice connection for bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + "). Check Rust logs for details. Returned: " + vcName);
            } else {
                platform.info("Started voice chat for group in channel '" + vcName + "' with bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + ")");
            }
        } catch (Throwable e) {
            platform.error("Failed to start voice connection for bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + "). Check Rust logs for details.", e);
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
        if (freed || ptr == 0) {
            platform.warn("Attempted to stop after bot was freed or ptr was invalid");
            return;
        }
        try {
            stopDiscordAudioThread();
            if (deleteChannel) {
                deleteDiscordVoiceChannelAsync(() -> {
                    try {
                        _stop(ptr);
                    } catch (Throwable e) {
                        platform.error("Failed to stop bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + "). Check Rust logs for details.", e);
                    }
                    platform.info("DiscordBot.stop finished for vcid=" + discordChannelId + " (categoryId=" + categoryId + ")");
                });
            } else {
                try {
                    _stop(ptr);
                } catch (Throwable e) {
                    platform.error("Failed to stop bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + "). Check Rust logs for details.", e);
                }
                platform.info("DiscordBot.stop finished for vcid=" + discordChannelId + " (categoryId=" + categoryId + ")");
            }
        } catch (Throwable e) {
            platform.error("Failed to stop bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + "). Check Rust logs for details.", e);
        }
    }

    private native void _free(long ptr);

    /**
     * Safety: the class should be discarded after calling
     */
    public void free() {
        freed = true;
        stopDiscordAudioThread();
        if (ptr != 0) {
            _free(ptr);
            ptr = 0;
        }
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
                platform.warn("[handleGroupMicrophonePacketEvent] Packet is not convertable to StaticSoundPacket! (vcid=" + channelId + ")");
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
        if (freed || ptr == 0) {
            platform.warn("handlePacket called after bot was freed or ptr was invalid (vcid=" + discordChannelId + ")");
            return;
        }
        if (player == null) {
            platform.warn("handlePacket called with null player (vcid=" + discordChannelId + ")");
            return;
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            platform.warn("StaticSoundPacket missing playerId (vcid=" + discordChannelId + ")");
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
        if (freed || ptr == 0) {
            platform.warn("Attempted to disconnect after bot was freed or ptr was invalid");
            return;
        }
        try {
            _disconnect(ptr);
            platform.info("Disconnected Discord bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + ") from voice channel (without deleting). ");
        } catch (Throwable e) {
            platform.error("Failed to disconnect Discord bot (vcid=" + discordChannelId + ", categoryId=" + categoryId + "). Check Rust logs for details.", e);
        }
    }

    // Native method for disconnecting from the voice channel without deleting it
    private native void _disconnect(long ptr);

    /**
     * Removes all StaticAudioChannels for a Discord user ID from a group for all players.
     */
    public static void removeDiscordUserChannelsFromGroup(UUID groupId, Long discordUserId) {
        var groupChannels = GroupManager.groupAudioChannels.get(groupId);
        if (groupChannels != null) {
            for (var playerChannels : groupChannels.values()) {
                playerChannels.remove(discordUserId);
            }
        }
    }

    /**
     * Encodes a long as a base-26 string using only a-z, max 16 chars.
     */
    private static String toBase26CategoryId(long id) {
        if (id == 0) return "a";
        StringBuilder sb = new StringBuilder();
        long n = id;
        while (n > 0) {
            int rem = (int)(n % 26);
            sb.append((char)('a' + rem));
            n /= 26;
        }
        String result = sb.reverse().toString();
        if (result.length() > 16) {
            result = result.substring(result.length() - 16);
        }
        return result;
    }

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

        // Build set of channel IDs the bots are currently in
        java.util.Set<Long> botChannelIds = new java.util.HashSet<>();
        for (var bot : GroupManager.groupBotMap.values()) {
            Long botChan = bot != null ? bot.getDiscordChannelId() : null;
            if (botChan != null) botChannelIds.add(botChan);
        }

        // Only process if the new channel or previous channel is one we care about
        Long previousChannelId = GroupManager.discordUserChannelMap.get(discordUserId);
        boolean caresAboutNew = channelId != 0L && botChannelIds.contains(channelId);
        boolean caresAboutOld = previousChannelId != null && previousChannelId != 0L && botChannelIds.contains(previousChannelId);

        // If user is switching from a tracked channel to an untracked one, treat as leaving; otherwise, ignore if neither is tracked
        if (!caresAboutNew) {
            if (caresAboutOld) {
                channelId = 0L;
            } else {
                return;
            }
        }

        // If user is switching channels, show both leave and join messages
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
                    removeDiscordUserChannelsFromGroup(oldGroupId, discordUserId);
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

        platform.info("[DiscordBot] User " + (joined ? "joined" : "left") + " Discord VC: " + username + " (ID: " + discordUserId + ", vcid=" + channelId + ")");

        Long groupChannelId;
        if (joined) {
            groupChannelId = channelId;
        } else {
            // On leave, use previous channel
            groupChannelId = previousChannelId;
            // Optimization: if user is already not present, skip all work
            Long existing = GroupManager.discordUserChannelMap.get(discordUserId);
            if (existing == null || existing == 0L) {
                platform.debug("[DiscordBot] User " + discordUserId + " already not in any channel, skipping leave event.");
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
            platform.info("[DiscordBot] No group found for Discord channel ID " + groupChannelId + " (vcid=" + groupChannelId + ")");
            return;
        }

        if (joined) {
            // If already in the correct channel, skip (avoid duplicate join event)
            Long existing = GroupManager.discordUserChannelMap.get(discordUserId);
            if (existing != null && existing.equals(channelId)) {
                platform.debug("[DiscordBot] User " + discordUserId + " already in channel " + channelId + ", skipping join event.");
                return;
            }
            GroupManager.discordUserChannelMap.put(discordUserId, channelId);
            GroupManager.discordUserNameMap.put(discordUserId, username);

            // Create/register a unique category for this Discord user if not already present
            // Use a base-26 (a-z) encoding for the category ID, max 16 chars
            String categoryId = toBase26CategoryId(discordUserId);
            if (!discordUserCategoryMap.containsKey(discordUserId)) {
                // Truncate username to 16 characters to fit VoiceChat's limit, add "..." if truncated
                String truncatedName;
                if (username.length() > 16) {
                    truncatedName = username.substring(0, 13) + "...";
                } else {
                    truncatedName = username;
                }
                var icon = Core.loadDiscordIcon();
                var category = Core.api.volumeCategoryBuilder()
                    .setId(categoryId)
                    .setName(truncatedName)
                    .setDescription("Volume for Discord user " + username)
                    .setIcon(icon)
                    .build();
                Core.api.registerVolumeCategory(category);
                discordUserCategoryMap.put(discordUserId, categoryId);
                platform.info("Registered volume category for Discord user '" + username + "' (ID: " + discordUserId + ", catId: " + categoryId + ")");
            }

            // Create a StaticAudioChannel for this Discord user for every player in the group, and assign the category
            var groupPlayers = GroupManager.groupPlayerMap.get(foundGroupId);
            if (groupPlayers != null) {
                for (var player : groupPlayers) {
                    var playerId = player.getUuid();
                    var connection = Core.api.getConnectionOf(playerId);
                    var level = player.getServerLevel();
                    if (connection != null && level != null) {
                        var channelIdUuid = java.util.UUID.randomUUID();
                        var staticChannel = Core.api.createStaticAudioChannel(channelIdUuid, level, connection);
                        if (staticChannel != null) {
                            staticChannel.setCategory(categoryId);
                            GroupManager.groupAudioChannels
                                .computeIfAbsent(foundGroupId, k -> new java.util.HashMap<>())
                                .computeIfAbsent(playerId, k -> new java.util.HashMap<>())
                                .put(discordUserId, staticChannel);
                            platform.info("Created StaticAudioChannel for Discord user '" + username + "' and player " + playerId + " in group " + foundGroupId + " with category " + categoryId);
                        } else {
                            platform.error("Failed to create StaticAudioChannel for Discord user '" + username + "' and player " + playerId + " in group " + foundGroupId);
                        }
                    }
                }
            }
        } else {
            // On leave, look up the last channel they were in
            GroupManager.discordUserChannelMap.remove(discordUserId);
            GroupManager.discordUserNameMap.remove(discordUserId);
            if (groupChannelId == null || groupChannelId == 0L) {
                platform.info("[DiscordBot] No previous channel found for user " + discordUserId + " (vcid=null), cannot send leave message.");
                return;
            }

            // Remove all StaticAudioChannels for this Discord user for every player in the group
            removeDiscordUserChannelsFromGroup(foundGroupId, discordUserId);
            // Only unregister the category if the new channelId is zero (user left VC, not just switched)
            if (channelId == 0L) {
                String categoryId = discordUserCategoryMap.remove(discordUserId);
                if (categoryId != null) {
                    Core.api.unregisterVolumeCategory(categoryId);
                    platform.info("Unregistered volume category for Discord user '" + username + "' (ID: " + discordUserId + ")");
                }
            }
        }

        // Get all players in the group and send them a message
        var players = GroupManager.groupPlayerMap.get(foundGroupId);
        if (players == null || players.isEmpty()) {
            platform.info("[DiscordBot] No players in group " + foundGroupId + " for Discord channel ID " + groupChannelId + " (vcid=" + groupChannelId + ")");
            return;
        }
        // Compose a message for Minecraft chat: [Discord] <username> joined/left the Discord voice channel.
        Component prefix = Component.blue("[Discord] ");
        Component name = Component.gold(username);
        Component action = joined
            ? Component.green(" joined the Discord voice channel.")
            : Component.red(" left the Discord voice channel.");
        for (var player : players) {
            platform.sendMessage(player, prefix, name, action);
        }
    }
    
    /**
     * Formats Discord emotes in a message to :name: for Minecraft display.
     * E.g. <a:Nod:123456789> or <:handrub:123456789> becomes :Nod: or :handrub:
     */
    public static String formatEmotes(String message) {
        if (message == null || message.indexOf(':') == -1) return message;
        
        java.util.regex.Matcher matcher = EMOTE_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, ":" + matcher.group(1) + ":");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Handles the !plist and !playerlist commands from Discord.
     * Sends a formatted list of current players in the group to Discord.
     */
    private void handlePlayerListCommand() {
        java.util.UUID groupId = null;
        // Find groupId for this bot
        for (var entry : GroupManager.groupBotMap.entrySet()) {
            if (entry.getValue() == this) {
                groupId = entry.getKey();
                break;
            }
        }
        
        if (groupId == null) {
            sendDiscordTextMessageAsync("Could not find associated group (**ERROR**)!");
            return;
        }

        var players = GroupManager.groupPlayerMap.get(groupId);
        if (players == null || players.isEmpty()) {
            sendDiscordTextMessageAsync("**Player List:** No players currently in the group.");
            return;
        }

        StringBuilder playerList = new StringBuilder();
        playerList.append("Player List (**").append(players.size());
        if (players.size() == 1) {
            playerList.append(" player**):\n");
        } else {
            playerList.append(" players**):\n");
        }

        // Sort player names alphabetically
        java.util.List<String> sortedNames = new java.util.ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            sortedNames.add(platform.getName(player));
        }
        java.util.Collections.sort(sortedNames, String.CASE_INSENSITIVE_ORDER);

        for (int i = 0; i < sortedNames.size(); i++) {
            String playerName = sortedNames.get(i);
            playerList.append("**").append(playerName).append("**");
            if (i < sortedNames.size() - 1) {
                if (sortedNames.size() == 2) {
                    playerList.append(" and ");
                } else if (i == sortedNames.size() - 2) {
                    playerList.append(", and ");
                } else {
                    playerList.append(", ");
                }
            }
        }

        sendDiscordTextMessageAsync(playerList.toString());
    }

    /**
     * Called from Rust when a Discord text message is sent in the managed VC channel.
     * Broadcasts the message to all group members in Simple Voice Chat.
     * @param author The Discord username
     * @param message The message content
     * @param channelId The Discord channel ID
     * @param attachmentsArr String[][] from JNI, where each element is [filename, url]
     */
    public void onDiscordTextMessage(String author, long authorId, String message, long channelId, String[][] attachmentsArr) {
        // Only process if this bot's channel matches
        if (freed || ptr == 0) return;
        if (discordChannelId == null || discordChannelId != channelId) return;

        // Ignore messages from any bot user ID
        if (Core.botUserIds != null && Core.botUserIds.contains(authorId)) return;

        // Mark that a user message broke the join/leave chain
        lastMessageWasJoinLeave = false;

        // Check for player list commands
        String trimmedMessage = message.trim();
        if (trimmedMessage.equalsIgnoreCase("!plist") || trimmedMessage.equalsIgnoreCase("!playerlist") || trimmedMessage.equalsIgnoreCase("!pl")) {
            handlePlayerListCommand();
            return;
        }

        String formattedMessage = DiscordBot.formatEmotes(message);

        // Format message for Minecraft group chat with colored [Discord] prefix
        Component prefix = Component.blue("[Discord] ");
        Component name = Component.gold(author);
        Component msg = Component.white(": " + formattedMessage);
        
        Component attachmentsChain = null;
        if (attachmentsArr != null && attachmentsArr.length > 0) {
            for (int i = 0; i < attachmentsArr.length; i++) {
                String[] tuple = attachmentsArr[i];
                if (tuple != null && tuple.length == 2) {
                    String filename = tuple[0];
                    String url = tuple[1];
                    String label = "[" + filename + "]";
                    Component link = Component.aqua(label)
                        .withClickUrl(url)
                        .withHoverText(url);
                    if (attachmentsChain == null) {
                        attachmentsChain = link;
                    } else {
                        Component separator = Component.white(" ");
                        attachmentsChain = attachmentsChain.append(separator).append(link);
                    }
                } else {
                    platform.warn("[DiscordBot] Skipping invalid attachment tuple at index " + i + ": " + java.util.Arrays.toString(tuple));
                }
            }
            if (attachmentsChain != null) {
                Component space = Component.white(" ");
                msg = msg.append(space).append(attachmentsChain);
            }
        }

        java.util.UUID groupId = null;
        // Find groupId for this bot
        for (var entry : GroupManager.groupBotMap.entrySet()) {
            if (entry.getValue() == this) {
                groupId = entry.getKey();
                break;
            }
        }
        if (groupId == null) return;

        var players = GroupManager.groupPlayerMap.get(groupId);
        if (players == null || players.isEmpty()) return;

        for (var player : players) {
            platform.sendMessage(player, prefix, name, msg);
        }
    }
}