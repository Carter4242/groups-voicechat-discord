package dev.amsam0.voicechatdiscord;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static dev.amsam0.voicechatdiscord.Constants.RELOAD_CONFIG_PERMISSION;
import static dev.amsam0.voicechatdiscord.Core.*;

/**
 * Subcommands for /dvc
 */
public final class SubCommands {
    @SuppressWarnings("unchecked")
    public static <S> LiteralArgumentBuilder<S> build(LiteralArgumentBuilder<S> builder) {
        return (LiteralArgumentBuilder<S>) ((LiteralArgumentBuilder<Object>) builder)
                .then(literal("reloadconfig").executes(wrapInTry(SubCommands::reloadConfig)));
    }

    private static <S> Command<S> wrapInTry(java.util.function.Consumer<CommandContext<?>> function) {
        return (sender) -> {
            try {
                function.accept(sender);
            } catch (Throwable e) {
                platform.error("An error occurred when running a command", e);
                platform.sendMessage(sender, Component.red("An error occurred when running the command. Please check the console or tell your server owner to check the console."));
            }
            return 1;
        };
    }

    private static void reloadConfig(CommandContext<?> sender) {
        if (!platform.isOperator(sender) && !platform.hasPermission(
                sender,
                RELOAD_CONFIG_PERMISSION
        )) {
            platform.sendMessage(
                    sender,
                    Component.red("You must be an operator or have the `" + RELOAD_CONFIG_PERMISSION + "` permission to use this command!")
            );
            return;
        }

        platform.sendMessage(sender, Component.yellow("Stopping bots..."));

        new Thread(() -> {
            for (DiscordBot bot : bots)
                if (bot.player() != null)
                    platform.sendMessage(
                            bot.player(),
                            Component.red("The config is being reloaded which stops all bots. Please use "),
                            Component.white("/dvc start"),
                            Component.red(" to restart your session.")
                    );

            clearBots();

            platform.sendMessage(
                    sender,
                    Component.green("Successfully stopped bots! "),
                    Component.yellow("Reloading config...")
            );

            loadConfig();

            platform.sendMessage(
                    sender,
                    Component.green("Successfully reloaded config! Using " + bots.size() + " bot" + (bots.size() != 1 ? "s" : "") + ".")
            );
        }, "voicechat-discord: Reload Config").start();
    }
}
