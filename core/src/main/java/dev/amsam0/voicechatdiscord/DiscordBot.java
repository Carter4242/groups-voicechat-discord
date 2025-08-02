package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.audiolistener.AudioListener;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import dev.amsam0.voicechatdiscord.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static dev.amsam0.voicechatdiscord.Core.api;
import static dev.amsam0.voicechatdiscord.Core.platform;

public final class DiscordBot {
    // Add a player to the group and register their listener/sender
    public void addPlayerToGroup(ServerPlayer player) {
        if (listeners.containsKey(player.getUuid())) return;
        var connection = api.getConnectionOf(player);
        if (connection == null) {
            platform.warn("No connection found for player " + platform.getName(player) + " (" + player.getUuid() + ")");
            return;
        }

        platform.debug("Attempting to register audio listener for " + platform.getName(player) + " (" + player.getUuid() + ")");
        AudioListener listener = api.playerAudioListenerBuilder()
                .setPacketListener(packet -> handlePacket(packet, player))
                .setPlayer(player.getUuid())
                .build();
        api.registerAudioListener(listener);
        listeners.put(player.getUuid(), listener);

        platform.debug("Attempting to create audio sender for connection: " + connection);
        AudioSender sender = api.createAudioSender(connection);
        platform.debug("AudioSender created: " + sender);
        boolean registered = api.registerAudioSender(sender);
        platform.debug("registerAudioSender returned: " + registered);
        if (registered) {
            senders.put(player.getUuid(), sender);
        } else {
            platform.error("Couldn't register audio sender for " + platform.getName(player) + ". Connection: " + connection + ", Sender: " + sender);
        }
    }

    // Remove a player from the group and unregister their listener/sender
    public void removePlayerFromGroup(ServerPlayer player) {
        AudioListener listener = listeners.remove(player.getUuid());
        if (listener != null) {
            try {
                api.unregisterAudioListener(listener);
            } catch (Throwable e) {
                platform.error("Failed to unregister listener for " + platform.getName(player), e);
            }
        }
        AudioSender sender = senders.remove(player.getUuid());
        if (sender != null) {
            try {
                sender.reset();
                api.unregisterAudioSender(sender);
            } catch (Throwable e) {
                platform.error("Failed to unregister sender for " + platform.getName(player), e);
            }
        }
    }
    // Make sure to mirror this value on the Rust side (`DiscordBot::reset_senders::DURATION_UNTIL_RESET`)
    private static final int MILLISECONDS_UNTIL_RESET = 1000;
    /**
     * ID for the voice channel the bot is assigned to
     */
    private final long vcId;
    /**
     * Pointer to Rust struct
     */
    private final long ptr;
    // Listeners and senders for all players in the first group
    private final Map<UUID, AudioListener> listeners = new HashMap<>();
    private final Map<UUID, AudioSender> senders = new HashMap<>();
    /**
     * The SVC audio sender used to send audio to SVC.
     */
    private AudioSender sender;
    /**
     * A thread that sends opus data to the AudioSender.
     */
    private Thread senderThread;
    /**
     * The last time (unix timestamp) that audio was sent to the audio sender.
     */
    private Long lastTimeAudioProvidedToSVC;
    /**
     * A thread that checks every 500ms if the audio sender, discord encoder and audio source decoders should be reset.
     */
    private Thread resetThread;
    /**
     * The SVC audio listener to listen for outgoing (to Discord) audio.
     */
    private AudioListener listener;
    private int connectionNumber = 0;

    public @Nullable ServerPlayer player() {
        // Deprecated: no longer player-centric
        return null;
    }

    public boolean whispering() {
        return sender.isWhispering();
    }

    public void whispering(boolean set) {
        sender.whispering(set);
    }

    private static native long _new(String token, long vcId);

    public DiscordBot(String token, long vcId) {
        this.vcId = vcId;
        ptr = _new(token, vcId);
    }

    public void logInAndStart(ServerPlayer player) {
        // Deprecated: no longer player-centric
        if (logIn()) {
            start();
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

            // Get the first group and its players
            UUID groupId = dev.amsam0.voicechatdiscord.GroupManager.firstGroupId;
            if (groupId == null) {
                platform.warn("No group to link to Discord VC");
                return;
            }
            List<ServerPlayer> groupPlayers = dev.amsam0.voicechatdiscord.GroupManager.groupPlayers.get(groupId);
            if (groupPlayers == null || groupPlayers.isEmpty()) {
                platform.warn("No players in the first group");
                return;
            }

            for (ServerPlayer player : groupPlayers) {
                var connection = api.getConnectionOf(player);
                if (connection == null) {
                    platform.warn("No connection found for player " + platform.getName(player) + " (" + player.getUuid() + ")");
                    continue;
                }

                platform.debug("Attempting to register audio listener for " + platform.getName(player) + " (" + player.getUuid() + ")");
                AudioListener listener = api.playerAudioListenerBuilder()
                        .setPacketListener(packet -> handlePacket(packet, player))
                        .setPlayer(player.getUuid())
                        .build();
                api.registerAudioListener(listener);
                listeners.put(player.getUuid(), listener);

                platform.debug("Attempting to create audio sender for connection: " + connection);
                AudioSender sender = api.createAudioSender(connection);
                platform.debug("AudioSender created: " + sender);
                boolean registered = api.registerAudioSender(sender);
                platform.debug("registerAudioSender returned: " + registered);
                if (registered) {
                    senders.put(player.getUuid(), sender);
                } else {
                    platform.error("Couldn't register audio sender for " + platform.getName(player) + ". Connection: " + connection + ", Sender: " + sender);
                }
            }

            platform.info("Started voice chat for group in channel " + vcName + " with bot with vc_id " + vcId);
        } catch (Throwable e) {
            platform.error("Failed to start voice connection for bot with vc_id " + vcId, e);
        }
    }

    private native void _stop(long ptr) throws Throwable;

    public void stop() {
        // This method is very conservative about failing - we want to always return to a decent state even if things don't work out

        // Unregister all listeners and senders for group players
        for (AudioListener listener : listeners.values()) {
            try {
                api.unregisterAudioListener(listener);
            } catch (Throwable e) {
                platform.error("Failed to stop bot (listener)", e);
            }
        }
        listeners.clear();
        for (AudioSender sender : senders.values()) {
            try {
                sender.reset();
                api.unregisterAudioSender(sender);
            } catch (Throwable e) {
                platform.error("Failed to stop bot (sender)", e);
            }
        }
        senders.clear();

        try {
            if (resetThread != null) {
                resetThread.interrupt();
                for (int i = 0; i < 20; i++) {
                    if (resetThread != null && resetThread.isAlive()) {
                        try {
                            platform.debug("waiting for reset thread to end");
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                        }
                    } else {
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            platform.error("Failed to stop bot with vc_id " + vcId + " (reset thread)", e);
        }

        try {
            if (senderThread != null) {
                senderThread.interrupt(); // this really doesn't help stop the thread
                for (int i = 0; i < 20; i++) {
                    if (senderThread != null && senderThread.isAlive()) {
                        try {
                            platform.debug("waiting for sender thread to end");
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                        }
                    } else {
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            platform.error("Failed to stop bot with vc_id " + vcId + " (sender thread)", e);
        }

        // Threads are ended, so reset the connection number back to original (it will be incremented in start)
        // This way it doesn't jump from 1 to 3
        connectionNumber--;

        lastTimeAudioProvidedToSVC = null;
        listener = null;
        sender = null;
        resetThread = null;
        senderThread = null;

        // Stop the rust side last so that the state is still Started for any received packets
        try {
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

    private native void _addAudioToHearingBuffer(long ptr, int senderId, byte[] rawOpusData, boolean adjustBasedOnDistance, double distance, double maxDistance);

    public void handlePacket(SoundPacket packet, ServerPlayer player) {
        UUID senderId = packet.getSender();

        @Nullable Position position = null;
        double maxDistance = 0.0;
        boolean whispering = false;

        platform.debugExtremelyVerbose("packet is a " + packet.getClass().getSimpleName());
        if (packet instanceof EntitySoundPacket sound) {
            position = platform.getEntityPosition(player.getServerLevel(), sound.getEntityUuid());
            maxDistance = sound.getDistance();
            whispering = sound.isWhispering();
        } else if (packet instanceof LocationalSoundPacket sound) {
            position = sound.getPosition();
            maxDistance = sound.getDistance();
        } else if (!(packet instanceof StaticSoundPacket)) {
            platform.warn("packet is not LocationalSoundPacket, StaticSoundPacket or EntitySoundPacket, it is " + packet.getClass().getSimpleName() + ". Please report this on GitHub Issues!");
        }

        if (whispering) {
            platform.debugExtremelyVerbose("player is whispering, original max distance is " + maxDistance);
            maxDistance *= api.getServerConfig().getDouble("whisper_distance_multiplier", 1);
        }

        double distance = position != null
                ? Util.distance(position, player.getPosition())
                : 0.0;

        platform.debugExtremelyVerbose("adding audio for " + senderId);

        _addAudioToHearingBuffer(ptr, senderId.hashCode(), packet.getOpusEncodedData(), position != null, distance, maxDistance);
    }

    private native byte[] _blockForSpeakingBufferOpusData(long ptr);

    private native void _resetSenders(long ptr);
}