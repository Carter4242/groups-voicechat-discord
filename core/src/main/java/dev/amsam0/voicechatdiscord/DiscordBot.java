package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

import java.util.UUID;

import static dev.amsam0.voicechatdiscord.Core.platform;

public final class DiscordBot {
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

    /**
     * Send a list of Discord Opus audio packets to all group members in the specified group.
     * @param groupId The group to send audio to
     * @param opusPackets List of Opus encoded audio packets from Discord
     */
    public void sendDiscordAudioToGroup(UUID groupId, byte[][] opusPackets) {
        if (groupId == null) {
            platform.warn("No group to send Discord audio to");
            return;
        }
        var groupChannels = dev.amsam0.voicechatdiscord.GroupManager.groupAudioChannels.get(groupId);
        if (groupChannels == null || groupChannels.isEmpty()) {
            platform.warn("No group audio channels to send Discord audio to");
            return;
        }
        for (var entry : groupChannels.entrySet()) {
            UUID playerId = entry.getKey();
            var channelList = entry.getValue();
            if (channelList == null) {
                channelList = new java.util.ArrayList<>();
                groupChannels.put(playerId, channelList);
            }

            for (int i = 0; i < opusPackets.length; i++) {
                if (i >= channelList.size() || channelList.get(i) == null || channelList.get(i).isClosed()) {
                    // Only fetch these if we need to create a new channel
                    var connection = dev.amsam0.voicechatdiscord.Core.api.getConnectionOf(playerId);
                    var player = connection != null ? connection.getPlayer() : null;
                    var group = dev.amsam0.voicechatdiscord.Core.api.getGroup(groupId);
                    var level = player != null ? player.getServerLevel() : null;
                    if (group != null && level != null && connection != null) {
                        // Use a random UUID for the channelId instead of groupId
                        var randomChannelId = java.util.UUID.randomUUID();
                        var newChannel = dev.amsam0.voicechatdiscord.Core.api.createStaticAudioChannel(randomChannelId, level, connection);
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
                byte[] opusData = opusPackets[i];
                if (channel != null && !channel.isClosed() && opusData != null && opusData.length > 0) {
                    channel.send(opusData);
                }
            }
        }
    }

    private static native long _new(String token, long categoryId);

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
            platform.warn("Attempted to delete Discord channel after bot was freed");
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

                    if (result instanceof byte[][] packets) {
                        if (packets.length > 0) {
                            sendDiscordAudioToGroup(groupId, packets);
                        }
                    } else {
                        platform.warn("Unexpected return type from _blockForSpeakingBufferOpusData: " + (result == null ? "null" : result.getClass()));
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
        try {
            stopDiscordAudioThread();
            deleteDiscordVoiceChannelAsync(() -> {
                try {
                    _stop(ptr);
                } catch (Throwable e) {
                    platform.error("Failed to stop bot (categoryId=" + categoryId + ")", e);
                }
                platform.info("DiscordBot.stop finished for categoryId=" + categoryId);
            });
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
        platform.info("DiscordBot.free called for categoryId=" + categoryId);
        freed = true;
        stopDiscordAudioThread();
        deleteDiscordVoiceChannelAsync();
        _free(ptr);
        platform.info("DiscordBot.free finished for categoryId=" + categoryId);
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
            var bot = dev.amsam0.voicechatdiscord.GroupManager.groupBotMap.get(group.getId());
            if (bot == null) {
                return;
            }
            var sender = senderConn.getPlayer();
            if (sender == null) {
                return;
            }
            var packet = event.getPacket();
            if (!(packet instanceof de.maxhenkel.voicechat.api.packets.ConvertablePacket convertable)) {
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

    private native void _resetSenders(long ptr);
}