// Ported from Purpur (https://github.com/PurpurMC/Purpur), MIT License, Copyright (c) 2019-2024 PurpurMC.
package io.papermc.paper.fastpaper.bar;

import io.papermc.paper.configuration.GlobalConfiguration;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class TpsBarTask extends BossBarTask {
    public enum FillMode {
        TPS, MSPT, PING
    }

    private enum Level {
        GOOD, MEDIUM, LOW
    }

    private double tps = 20.0D;
    private double mspt = 0.0D;

    TpsBarTask() {
        super("tpsbar");
    }

    private static GlobalConfiguration.Commands.Tpsbar config() {
        return GlobalConfiguration.get().commands.tpsbar;
    }

    @Override
    protected int tickInterval() {
        return config().tickInterval;
    }

    @Override
    protected void sample() {
        this.tps = Math.max(0.0D, Math.min(20.0D, Bukkit.getTPS()[0]));
        this.mspt = Bukkit.getAverageTickTime();
    }

    @Override
    protected BossBar createBossBar() {
        return BossBar.bossBar(Component.empty(), 0.0F, BossBar.Color.GREEN, config().overlay);
    }

    @Override
    protected void updateBossBar(final BossBar bar, final Player player) {
        final GlobalConfiguration.Commands.Tpsbar config = config();
        final int ping = player.getPing();
        final float progress = switch (config.fillMode) {
            case TPS -> (float) this.tps / 20.0F;
            case MSPT -> (float) this.mspt / 50.0F;
            case PING -> ping / 300.0F;
        };
        bar.progress(clamp01(progress));
        bar.color(switch (this.level(config.fillMode, ping)) {
            case GOOD -> config.progressColor.good;
            case MEDIUM -> config.progressColor.medium;
            case LOW -> config.progressColor.low;
        });
        bar.overlay(config.overlay);
        bar.name(MiniMessage.miniMessage().deserialize(config.title,
            Placeholder.component("tps", this.text(config, FillMode.TPS, ping, String.format("%.2f", this.tps))),
            Placeholder.component("mspt", this.text(config, FillMode.MSPT, ping, String.format("%.2f", this.mspt))),
            Placeholder.component("ping", this.text(config, FillMode.PING, ping, Integer.toString(ping)))
        ));
    }

    private Level level(final FillMode mode, final int ping) {
        return switch (mode) {
            case TPS -> this.tps >= 19 ? Level.GOOD : this.tps >= 15 ? Level.MEDIUM : Level.LOW;
            case MSPT -> this.mspt < 40 ? Level.GOOD : this.mspt < 50 ? Level.MEDIUM : Level.LOW;
            case PING -> ping < 100 ? Level.GOOD : ping < 200 ? Level.MEDIUM : Level.LOW;
        };
    }

    private Component text(final GlobalConfiguration.Commands.Tpsbar config, final FillMode mode, final int ping, final String value) {
        final String format = switch (this.level(mode, ping)) {
            case GOOD -> config.textColor.good;
            case MEDIUM -> config.textColor.medium;
            case LOW -> config.textColor.low;
        };
        return MiniMessage.miniMessage().deserialize(format, Placeholder.unparsed("text", value));
    }
}
