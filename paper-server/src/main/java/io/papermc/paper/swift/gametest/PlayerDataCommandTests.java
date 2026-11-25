package io.papermc.paper.swift.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.dreeam.leaf.config.modules.gameplay.ModifyPlayerData;

/**
 * FastPaper - {@code /data} and {@code /execute store} on players
 * ({@code gameplay-mechanisms.modify-player-data}), typed by a fake player.
 *
 * <p>Both states of the switch run inside one test, one after the other on the main thread,
 * because the switch is global and tests in a batch run side by side.
 */
final class PlayerDataCommandTests {
    private PlayerDataCommandTests() {
    }

    static void modifyPlayerData(final GameTestHelper helper) {
        final SwiftFakePlayer op = SwiftFakePlayer.join(helper, "DataOp", new BlockPos(2, 1, 2), true);
        helper.runAfterDelay(1, () -> {
            final StringBuilder f = new StringBuilder();
            final boolean wasEnabled = ModifyPlayerData.enabled;
            final ServerPlayer p = op.player();
            try {
                ModifyPlayerData.enabled = false;
                op.command("scoreboard objectives add pd_test dummy");
                op.command("execute store success score #pd pd_test run data merge entity @s {foodLevel:3}");
                if (p.getFoodData().getFoodLevel() != 20) {
                    f.append("\n  switch off: foodLevel changed to ").append(p.getFoodData().getFoodLevel());
                }
                if (success(helper) != 0) {
                    f.append("\n  switch off: data merge on a player reported success");
                }

                ModifyPlayerData.enabled = true;
                final Vec3 start = p.position();

                op.command("execute store success score #pd pd_test run data merge entity @s {foodLevel:3}");
                check(f, "data merge success", success(helper), 1);
                check(f, "foodLevel", p.getFoodData().getFoodLevel(), 3);
                // The command writes back the whole tag; unchanged keys must not re-teleport.
                if (!p.position().equals(start)) {
                    f.append("\n  unrelated merge moved the player from ").append(start).append(" to ").append(p.position());
                }

                op.command("data modify entity @s Health set value 4f");
                check(f, "Health", p.getHealth(), 4.0F);

                op.command("execute store result entity @s Air int 1 run data get entity @s foodLevel");
                check(f, "Air from execute store", p.getAirSupply(), 3);

                op.command("data modify entity @s Tags append value \"pd_tag\"");
                if (!p.entityTags().contains("pd_tag")) {
                    f.append("\n  Tags append did not add the tag");
                }
                op.command("data remove entity @s Tags");
                if (!p.entityTags().isEmpty()) {
                    f.append("\n  Tags remove left ").append(p.entityTags());
                }

                op.command("data modify entity @s SelectedItemSlot set value 4");
                check(f, "SelectedItemSlot", p.getInventory().getSelectedSlot(), 4);

                op.command("data modify entity @s Inventory append value {Slot:5b,id:\"minecraft:diamond\",count:3}");
                if (!p.getInventory().getItem(5).is(Items.DIAMOND) || p.getInventory().getItem(5).getCount() != 3) {
                    f.append("\n  Inventory append: slot 5 holds ").append(p.getInventory().getItem(5));
                }

                final double baseSpeed = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
                op.command("data merge entity @s {active_effects:[{id:\"minecraft:speed\",amplifier:1b,duration:200}]}");
                if (!p.hasEffect(MobEffects.SPEED)) {
                    f.append("\n  active_effects merge did not add speed");
                } else if (!(p.getAttributeValue(Attributes.MOVEMENT_SPEED) > baseSpeed)) {
                    f.append("\n  speed effect did not apply its movement speed modifier");
                }
                op.command("data remove entity @s active_effects");
                if (!p.getActiveEffects().isEmpty()) {
                    f.append("\n  active_effects remove left ").append(p.getActiveEffects());
                }
                check(f, "movement speed after removing speed", p.getAttributeValue(Attributes.MOVEMENT_SPEED), baseSpeed);

                op.command("data merge entity @s {attributes:[{id:\"minecraft:max_health\",base:40d}]}");
                check(f, "max_health base", p.getAttributeBaseValue(Attributes.MAX_HEALTH), 40.0D);

                final BlockPos to = helper.absolutePos(new BlockPos(6, 1, 6));
                op.command("data merge entity @s {Pos:[" + (to.getX() + 0.5D) + "d," + to.getY() + "d," + (to.getZ() + 0.5D) + "d]}");
                if (p.blockPosition().getX() != to.getX() || p.blockPosition().getZ() != to.getZ()) {
                    f.append("\n  Pos merge left the player at ").append(p.blockPosition()).append(", wanted ").append(to);
                }
            } finally {
                ModifyPlayerData.enabled = wasEnabled;
                SwiftFakePlayer.leaveAll(helper);
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("modify player data mismatches:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    private static int success(final GameTestHelper helper) {
        final Scoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        final Objective objective = scoreboard.getObjective("pd_test");
        final ReadOnlyScoreInfo info = objective == null ? null : scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly("#pd"), objective);
        return info == null ? Integer.MIN_VALUE : info.value();
    }

    private static void check(final StringBuilder f, final String what, final double got, final double want) {
        if (got != want) {
            f.append("\n  ").append(what).append(": ").append(got).append(", expected ").append(want);
        }
    }
}
