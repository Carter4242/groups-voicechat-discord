package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.amsam0.voicechatdiscord.Core.platform;

/**
 * Manages cutaway functionality: temporarily teleporting group members and remembering their original positions.
 */
public final class CutawayManager {
    // Map sessionId -> Map(ServerPlayer -> originalPosition)
    // This stores both the player and their original position for quick access
    private static final Map<String, Map<ServerPlayer, PlayerPosition>> cutawaySessionPositions = new HashMap<>();
    private static int cutawayCounter = 0;

    /**
     * Starts a cutaway session and teleports all players in a group to a target location.
     * @param groupId The group ID
     * @param targetPosition The target position to teleport to
     * @param initiatorUuid The UUID of the player who initiated the command (will not be teleported)
     * @return A session ID to be used when teleporting players back
     */
    public static String startCutaway(UUID groupId, PlayerPosition targetPosition, UUID initiatorUuid) {
        List<ServerPlayer> players = GroupManager.groupPlayerMap.get(groupId);
        if (players == null || players.isEmpty()) {
            return null;
        }

        // Create a unique session ID for this cutaway
        String sessionId = "cutaway_" + groupId + "_" + (cutawayCounter++);
        Map<ServerPlayer, PlayerPosition> sessionPositions = new HashMap<>();
        cutawaySessionPositions.put(sessionId, sessionPositions);

        // Teleport each player and store their original position
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);

            try {
                // Get and store the player's current position
                PlayerPosition originalPos = platform.getPlayerPosition(player);
                if (originalPos != null) {
                    sessionPositions.put(player, originalPos);
                    // Teleport to target location
                    if (!player.getUuid().equals(initiatorUuid)) {
                        platform.teleportPlayer(player, targetPosition);
                    }
                }
            } catch (Exception e) {
                platform.error("Failed to teleport player " + player.getUuid() + ": " + e.getMessage(), e);
            }
        }

        return sessionId;
    }

    /**
     * Teleports all players in a cutaway session back to their original positions.
     * @param sessionId The session ID returned from startCutaway()
     * @param initiatorUuid The UUID of the player who initiated the command (will not be teleported back)
     */
    public static void endCutaway(String sessionId, UUID initiatorUuid) {
        Map<ServerPlayer, PlayerPosition> positions = cutawaySessionPositions.get(sessionId);
        
        if (positions == null || positions.isEmpty()) {
            return;
        }

        // Teleport each player back to their stored position
        for (Map.Entry<ServerPlayer, PlayerPosition> entry : positions.entrySet()) {
            ServerPlayer player = entry.getKey();
            PlayerPosition originalPos = entry.getValue();
            
            try {
                if (initiatorUuid == null || !player.getUuid().equals(initiatorUuid)) {
                    platform.teleportPlayer(player, originalPos);
                }
                // Play bubble pop sound when teleporting back
                platform.playPopSound(player);
            } catch (Exception e) {
                platform.error("Failed to teleport player " + player.getUuid() + " back: " + e.getMessage(), e);
            }
        }

        // Clear stored positions for this session
        cutawaySessionPositions.remove(sessionId);
    }
}
