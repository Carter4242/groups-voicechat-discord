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
    public final long vcId;
    private final long ptr;

    /**
     * Send a list of Discord Opus audio packets to all group members in the first group.
     * @param opusPackets List of Opus encoded audio packets from Discord
     */
    public void sendDiscordAudioToGroup(byte[][] opusPackets) {
        UUID groupId = dev.amsam0.voicechatdiscord.GroupManager.firstGroupId;
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

    private static native long _new(String token, long vcId);

    public DiscordBot(String token, long vcId) {
        this.vcId = vcId;
        ptr = _new(token, vcId);
    }

    /**
     * Starts the background thread for Discord audio bridging.
     */
    public void startDiscordAudioThread() {
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
                            sendDiscordAudioToGroup(packets);
                        }
                    } else {
                        platform.warn("Unexpected return type from _blockForSpeakingBufferOpusData: " + (result == null ? "null" : result.getClass()));
                    }
                } catch (Throwable t) {
                    platform.error("Error in Discord audio thread for bot vc_id=" + vcId, t);
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

    public void logInAndStart(ServerPlayer player) {
        if (logIn()) {
            start();
            startDiscordAudioThread(); // Start audio thread only after bot is ready
        }
    }

    private native boolean _isStarted(long ptr);

    public boolean isStarted() {
        return _isStarted(ptr);
    }

    private native void _logIn(long ptr) throws Throwable;

    private boolean logIn() {
        try {
            _logIn(ptr);
            platform.debug("Logged into the bot with vc_id " + vcId);
            return true;
        } catch (Throwable e) {
            platform.error("Failed to login to the bot with vc_id=" + vcId, e);
            return false;
        }
    }

    private native String _start(long ptr) throws Throwable;

    private void start() {
        try {
            String vcName = _start(ptr);
            platform.info("Started voice chat for group in channel " + vcName + " with bot with vc_id=" + vcId);
        } catch (Throwable e) {
            platform.error("Failed to start voice connection for bot with vc_id=" + vcId, e);
        }
    }

    private native void _stop(long ptr) throws Throwable;

    public void stop() {
        try {
            stopDiscordAudioThread();
            _stop(ptr);
        } catch (Throwable e) {
            platform.error("Failed to stop bot with vc_id=" + vcId, e);
        }
        platform.info("DiscordBot.stop finished for vc_id=" + vcId);
    }

    private native void _free(long ptr);

    /**
     * Safety: the class should be discarded after calling
     */
    public void free() {
        platform.info("DiscordBot.free called for vc_id=" + vcId);
        freed = true;
        stopDiscordAudioThread();
        _free(ptr);
        platform.info("DiscordBot.free finished for vc_id=" + vcId);
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
            var sender = senderConn.getPlayer();
            if (sender == null) {
                return;
            }
            if (Core.bots.isEmpty()) {
                platform.warn("[handleGroupMicrophonePacketEvent] No bots available!");
                return;
            }
            var packet = event.getPacket();
            if (!(packet instanceof de.maxhenkel.voicechat.api.packets.ConvertablePacket convertable)) {
                platform.warn("[handleGroupMicrophonePacketEvent] Packet is not convertable to StaticSoundPacket!");
                return;
            }
            Core.bots.get(0).handlePacket(convertable.staticSoundPacketBuilder().build(), sender);
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