package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.Player;

import java.util.Collection;
import java.util.UUID;

import static dev.amsam0.voicechatdiscord.Core.debugLevel;

public interface Platform {
    void sendMessage(Player player, Component... message);

    void sendActionBar(Player player, Component... message);

    String getName(Player player);

    String getConfigPath();

    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable throwable);

    void teleportPlayer(Player player, PlayerPosition position);

    PlayerPosition getPlayerPosition(Player player);

    void playPopSound(Player player);

    Collection<UUID> getOnlinePlayerUuids();

    default void debug(String message) {
        if (debugLevel >= 1) info("[DEBUG 1] " + message);
    }

    default void debug(String message, Throwable throwable) {
        if (debugLevel >= 1) error("[DEBUG 1] " + message, throwable);
    }
}
