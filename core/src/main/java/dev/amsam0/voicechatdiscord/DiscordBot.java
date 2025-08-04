package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

import java.util.Map;
import java.util.UUID;

import static dev.amsam0.voicechatdiscord.Core.api;
import static dev.amsam0.voicechatdiscord.Core.platform;

public final class DiscordBot {
    /**
     * Thread that polls for Discord audio and sends it to group members.
     */
    private Thread discordAudioThread;
    private volatile boolean running = false;
    // Discord connection and bridging logic
    private final long vcId;
    private final long ptr;

    /**
     * Send Discord Opus audio to all group members in the first group.
     * @param opusData Opus encoded audio data from Discord
     */
    public void sendDiscordAudioToGroup(byte[] opusData) {
        UUID groupId = dev.amsam0.voicechatdiscord.GroupManager.firstGroupId;
        if (groupId == null) {
            platform.warn("No group to send Discord audio to");
            return;
        }
        Map<UUID, de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel> channels = dev.amsam0.voicechatdiscord.GroupManager.groupAudioChannels.get(groupId);
        if (channels == null || channels.isEmpty()) {
            platform.warn("No group audio channels to send Discord audio to");
            return;
        }
        for (var entry : channels.entrySet()) {
            var channel = entry.getValue();
            if (channel != null && !channel.isClosed()) {
                channel.send(opusData);
            }
        }
    }

    /**
     * Handles a StaticSoundPacketEvent for bridging Minecraft group audio to Discord.
     */
    public static void handleStaticSoundPacketEvent(de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent event) {
        if (event.getPacket() != null && !Core.bots.isEmpty()) {
            var senderConn = event.getSenderConnection();
            if (senderConn != null) {
                var sender = senderConn.getPlayer();
                if (sender != null) {
                    Core.bots.get(0).handlePacket(event.getPacket(), sender);
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
        running = true;
        discordAudioThread = new Thread(() -> {
            while (running) {
                try {
                    byte[] opusData = _blockForSpeakingBufferOpusData(ptr);
                    if (opusData != null && opusData.length > 0) {
                        sendDiscordAudioToGroup(opusData);
                    }
                } catch (Throwable t) {
                    platform.error("Error in Discord audio thread", t);
                }
                try {
                    Thread.sleep(20); // ~50 packets/sec (20ms per Discord frame)
                } catch (InterruptedException ignored) {}
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
            platform.error("Failed to login to the bot with vc_id " + vcId, e);
            return false;
        }
    }

    private native String _start(long ptr) throws Throwable;

    private void start() {
        try {
            String vcName = _start(ptr);
            platform.info("Started voice chat for group in channel " + vcName + " with bot with vc_id " + vcId);
        } catch (Throwable e) {
            platform.error("Failed to start voice connection for bot with vc_id " + vcId, e);
        }
    }

    private native void _stop(long ptr) throws Throwable;

    public void stop() {
        try {
            stopDiscordAudioThread();
            _stop(ptr);
        } catch (Throwable e) {
            platform.error("Failed to stop bot with vc_id " + vcId, e);
        }
        platform.debug("Stopped bot with vc_id " + vcId);
    }

    private native void _free(long ptr);

    /**
     * Safety: the class should be discarded after calling
     */
    public void free() {
        _free(ptr);
    }

    private native void _addAudioToHearingBuffer(long ptr, byte[] groupIdBytes, byte[] rawOpusData);

    /**
     * Receives a group audio packet from Minecraft and sends it to Discord.
     */
    public void handlePacket(SoundPacket packet, ServerPlayer player) {
        if (!(packet instanceof StaticSoundPacket)) {
            platform.warn("handlePacket called with non-group packet: " + packet.getClass().getSimpleName());
            return;
        }
        // Extract group ID from VoicechatConnection
        Group group = null;
        if (player != null && api.getConnectionOf(player) != null) {
            group = api.getConnectionOf(player).getGroup();
        }
        UUID groupId = group != null ? group.getId() : null;
        if (groupId == null) {
            platform.warn("StaticSoundPacket missing groupId");
            return;
        }
        byte[] groupIdBytes = uuidToBytes(groupId);
        byte[] opusData = packet.getOpusEncodedData();
        _addAudioToHearingBuffer(ptr, groupIdBytes, opusData);
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

    private native byte[] _blockForSpeakingBufferOpusData(long ptr);

    private native void _resetSenders(long ptr);
}