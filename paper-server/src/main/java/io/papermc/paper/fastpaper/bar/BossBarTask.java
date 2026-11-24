// Ported from Purpur (https://github.com/PurpurMC/Purpur), MIT License, Copyright (c) 2019-2024 PurpurMC.
package io.papermc.paper.fastpaper.bar;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NullMarked;

/**
 * A per-player status boss bar. Every method runs on the main thread: commands and join/quit
 * hooks call in directly, and {@link StatusBars} schedules {@link #tick()} onto the main thread,
 * so the bars and the vanilla boss events behind them are never touched concurrently.
 */
@NullMarked
public abstract class BossBarTask {
    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();
    private final NamespacedKey key;
    private int tick;

    private record Shown(Player player, BossBar bar) {
    }

    protected BossBarTask(final String keyName) {
        this.key = new NamespacedKey("fastpaper", keyName);
    }

    protected abstract BossBar createBossBar();

    protected abstract int tickInterval();

    /** Samples the values shown by the bar; called once per update before the bars are redrawn. */
    protected abstract void sample();

    protected abstract void updateBossBar(BossBar bar, Player player);

    final void tick() {
        if (++this.tick < Math.max(1, this.tickInterval())) {
            return;
        }
        this.tick = 0;
        if (this.shown.isEmpty()) {
            return;
        }
        this.sample();
        this.shown.values().removeIf(entry -> !entry.player().isOnline());
        for (final Shown entry : this.shown.values()) {
            this.updateBossBar(entry.bar(), entry.player());
        }
    }

    /** Toggles the bar for a player and remembers the choice in their player data. */
    public boolean toggle(final Player player) {
        final boolean enable = !this.shown.containsKey(player.getUniqueId());
        if (enable) {
            this.show(player);
        } else {
            this.hide(player);
        }
        player.getPersistentDataContainer().set(this.key, PersistentDataType.BOOLEAN, enable);
        return enable;
    }

    void restore(final Player player) {
        if (player.getPersistentDataContainer().getOrDefault(this.key, PersistentDataType.BOOLEAN, false)) {
            this.sample();
            this.show(player);
        }
    }

    void show(final Player player) {
        this.hide(player);
        final BossBar bar = this.createBossBar();
        this.shown.put(player.getUniqueId(), new Shown(player, bar));
        this.updateBossBar(bar, player);
        player.showBossBar(bar);
    }

    void hide(final Player player) {
        final Shown entry = this.shown.remove(player.getUniqueId());
        if (entry != null) {
            player.hideBossBar(entry.bar());
        }
    }

    static float clamp01(final float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
