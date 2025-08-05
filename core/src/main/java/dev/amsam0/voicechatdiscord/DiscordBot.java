package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.Group;
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
    private volatile boolean freed = false;
    // Discord connection and bridging logic
    public final long vcId;
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
     * Handles a MicrophonePacketEvent for bridging Minecraft group audio to Discord (including solo group members).
     */
    public static void handleGroupMicrophonePacketEvent(de.maxhenkel.voicechat.api.events.MicrophonePacketEvent event) {
        try {
            var senderConn = event.getSenderConnection();
            if (senderConn == null) {
                platform.info("[handleGroupMicrophonePacketEvent] No sender connection, ignoring.");
                return;
            }
            var group = senderConn.getGroup();
            if (group == null) {
                return;
            }
            var sender = senderConn.getPlayer();
            if (sender == null) {
                platform.info("[handleGroupMicrophonePacketEvent] No sender player, ignoring.");
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
                    byte[] opusData = _blockForSpeakingBufferOpusData(ptr);
                    if (freed) break;

                    if (opusData != null && opusData.length > 0) {
                        sendDiscordAudioToGroup(opusData);
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

    private native void _addAudioToHearingBuffer(long ptr, byte[] groupIdBytes, byte[] rawOpusData);

    /**
     * Receives a group audio packet from Minecraft and sends it to Discord.
     */
    public void handlePacket(StaticSoundPacket packet, ServerPlayer player) {
        if (freed) {
            platform.warn("handlePacket called after bot was freed");
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
        platform.info("[handlePacket] opusData length=" + (opusData != null ? opusData.length : -1));
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