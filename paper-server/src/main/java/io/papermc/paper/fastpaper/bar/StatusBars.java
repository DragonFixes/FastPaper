// Ported from Purpur (https://github.com/PurpurMC/Purpur), MIT License, Copyright (c) 2019-2024 PurpurMC.
package io.papermc.paper.fastpaper.bar;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

/**
 * The /tpsbar and /rambar boss bars. Each player's choice is stored in their persistent data
 * container, so it survives relogs and restarts.
 */
@NullMarked
public final class StatusBars {
    public static final TpsBarTask TPS = new TpsBarTask();
    public static final RamBarTask RAM = new RamBarTask();
    private static final List<BossBarTask> ALL = List.of(TPS, RAM);

    public static final String TPSBAR_DESCRIPTION = "Toggles a boss bar showing TPS, MSPT and ping";
    public static final String RAMBAR_DESCRIPTION = "Toggles a boss bar showing heap memory use";

    private StatusBars() {
    }

    /** Called on the main thread once per server tick. */
    public static void tick() {
        for (final BossBarTask task : ALL) {
            task.tick();
        }
    }

    /** Called on the main thread after a player has joined. */
    public static void onJoin(final Player player) {
        for (final BossBarTask task : ALL) {
            task.restore(player);
        }
    }

    /** Called on the main thread when a player leaves. */
    public static void onQuit(final Player player) {
        for (final BossBarTask task : ALL) {
            task.hide(player);
        }
    }

    public static LiteralCommandNode<CommandSourceStack> tpsbarCommand() {
        return command("tpsbar", TPS, config -> config.tpsbar.commandOutput);
    }

    public static LiteralCommandNode<CommandSourceStack> rambarCommand() {
        return command("rambar", RAM, config -> config.rambar.commandOutput);
    }

    private static LiteralCommandNode<CommandSourceStack> command(
        final String name,
        final BossBarTask task,
        final Function<io.papermc.paper.configuration.GlobalConfiguration.Commands, String> output
    ) {
        final String permission = "bukkit.command." + name;
        return Commands.literal(name)
            .requires(source -> source.getSender().hasPermission(permission))
            .executes(ctx -> {
                if (!(ctx.getSource().getExecutor() instanceof final Player player)) {
                    ctx.getSource().getSender().sendMessage(Component.text("Only players can toggle their own " + name + "; use /" + name + " <targets>.", NamedTextColor.RED));
                    return 0;
                }
                return toggle(ctx, task, output, List.of(player));
            })
            .then(Commands.argument("targets", ArgumentTypes.players())
                .requires(source -> source.getSender().hasPermission(permission + ".other"))
                .executes(ctx -> toggle(ctx, task, output,
                    ctx.getArgument("targets", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()))))
            .build();
    }

    private static int toggle(
        final CommandContext<CommandSourceStack> ctx,
        final BossBarTask task,
        final Function<io.papermc.paper.configuration.GlobalConfiguration.Commands, String> output,
        final Collection<Player> targets
    ) throws CommandSyntaxException {
        final String format = output.apply(io.papermc.paper.configuration.GlobalConfiguration.get().commands);
        for (final Player player : targets) {
            final boolean enabled = task.toggle(player);
            ctx.getSource().getSender().sendMessage(MiniMessage.miniMessage().deserialize(format,
                Placeholder.component("onoff", Component.translatable(enabled ? "options.on" : "options.off",
                    enabled ? NamedTextColor.GREEN : NamedTextColor.RED)),
                Placeholder.unparsed("target", player.getName())));
        }
        return targets.isEmpty() ? 0 : targets.size();
    }
}
