package dev.amsam0.voicechatdiscord;

import com.mojang.brigadier.context.CommandContext;
import de.maxhenkel.voicechat.api.Player;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

import static dev.amsam0.voicechatdiscord.Core.debugLevel;

public interface Platform {
    @Nullable Position getEntityPosition(ServerLevel level, UUID uuid);

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean isValidPlayer(CommandContext<?> sender);

    ServerPlayer commandContextToPlayer(CommandContext<?> context);

    boolean isOperator(CommandContext<?> sender);

    boolean hasPermission(CommandContext<?> sender, String permission);

    void sendMessage(CommandContext<?> sender, Component... message);

    void sendMessage(Player player, Component... message);

    String getName(Player player);

    void setOnPlayerLeaveHandler(Consumer<UUID> handler);

    @Nullable String getSimpleVoiceChatVersion();

    String getConfigPath();

    Loader getLoader();

    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable throwable);

    default void debug(String message) {
        if (debugLevel >= 1) info("[DEBUG 1] " + message);
    }

    default void debugVerbose(String message) {
        if (debugLevel >= 2) info("[DEBUG 2] " + message);
    }

    default void debugExtremelyVerbose(String message) {
        if (debugLevel >= 3) info("[DEBUG 3] " + message);
    }

    default void debug(String message, Throwable throwable) {
        if (debugLevel >= 1) error("[DEBUG 1] " + message, throwable);
    }

    enum Loader {
        PAPER("paper");

        public final String modrinthName;

        Loader(String modrinthName) {
            this.modrinthName = modrinthName;
        }
    }
}
