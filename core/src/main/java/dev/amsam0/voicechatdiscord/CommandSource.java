package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** Whoever ran a command, as seen by the platform-independent command code. */
public interface CommandSource {
    /** The player running the command, or null for console / command blocks. */
    @Nullable ServerPlayer player();

    boolean hasPermission(String permission);

    void sendMessage(Component... message);
}
