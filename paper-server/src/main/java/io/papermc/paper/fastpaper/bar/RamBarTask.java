// Ported from Purpur (https://github.com/PurpurMC/Purpur), MIT License, Copyright (c) 2019-2024 PurpurMC.
package io.papermc.paper.fastpaper.bar;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class RamBarTask extends BossBarTask {
    private long allocated;
    private long used;
    private long xmx;
    private long xms;
    private float percent;

    RamBarTask() {
        super("rambar");
    }

    private static GlobalConfiguration.Commands.Rambar config() {
        return GlobalConfiguration.get().commands.rambar;
    }

    @Override
    protected int tickInterval() {
        return config().tickInterval;
    }

    @Override
    protected void sample() {
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        this.allocated = heap.getCommitted();
        this.used = heap.getUsed();
        this.xms = heap.getInit();
        // getMax() is -1 when the heap has no defined limit; fall back to the committed size.
        this.xmx = heap.getMax() > 0 ? heap.getMax() : this.allocated;
        this.percent = this.xmx > 0 ? clamp01((float) this.used / this.xmx) : 0.0F;
    }

    @Override
    protected BossBar createBossBar() {
        return BossBar.bossBar(Component.empty(), 0.0F, BossBar.Color.GREEN, config().overlay);
    }

    @Override
    protected void updateBossBar(final BossBar bar, final Player player) {
        final GlobalConfiguration.Commands.Rambar config = config();
        bar.progress(this.percent);
        bar.color(this.percent < 0.5F ? config.progressColor.good
            : this.percent < 0.75F ? config.progressColor.medium
            : config.progressColor.low);
        bar.overlay(config.overlay);
        bar.name(MiniMessage.miniMessage().deserialize(config.title,
            Placeholder.component("allocated", this.format(config, this.allocated)),
            Placeholder.component("used", this.format(config, this.used)),
            Placeholder.component("xmx", this.format(config, this.xmx)),
            Placeholder.component("xms", this.format(config, this.xms)),
            Placeholder.unparsed("percent", ((int) (this.percent * 100)) + "%")
        ));
    }

    private Component format(final GlobalConfiguration.Commands.Rambar config, final long bytes) {
        final String color = this.percent < 0.60F ? config.textColor.good
            : this.percent < 0.85F ? config.textColor.medium
            : config.textColor.low;
        final String value;
        if (bytes < 1024) {
            value = bytes + "B";
        } else {
            final int unit = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
            value = String.format("%.1f%s", (double) bytes / (1L << (unit * 10)), "BKMGTPE".charAt(unit));
        }
        return MiniMessage.miniMessage().deserialize(color, Placeholder.unparsed("text", value));
    }
}
