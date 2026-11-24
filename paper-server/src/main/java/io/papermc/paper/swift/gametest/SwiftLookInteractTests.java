package io.papermc.paper.swift.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.GameType;
import org.slf4j.LoggerFactory;

/**
 * FastPaper - tests for the look-and-interact type index ({@code swift.lookInteractTypeIndex}): looking for the
 * closest entity of one type among a sensor's visible entities must find exactly what the full-list walk finds,
 * after the same line-of-sight tests.
 */
final class SwiftLookInteractTests {
    private SwiftLookInteractTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    /**
     * A crowd of villagers and piglins with two players among them. For every villager's sensed visible
     * entities, and for several entity types and distance limits, the indexed search and the full-list search
     * return the same entity. At least some searches must find a player, so the match path is exercised.
     */
    static void differential(final GameTestHelper helper) {
        final SwiftFakePlayer near = SwiftFakePlayer.join(helper, "LookA", new BlockPos(3, 1, 3), false);
        final SwiftFakePlayer far = SwiftFakePlayer.join(helper, "LookB", new BlockPos(7, 1, 7), false);
        near.player().setGameMode(GameType.SURVIVAL);
        far.player().setGameMode(GameType.SURVIVAL);
        final List<Villager> villagers = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            final BlockPos at = new BlockPos(1 + i % 7, 1, 1 + (i / 7) % 7);
            if (i % 5 == 4) {
                final Mob piglin = helper.spawn(EntityTypes.PIGLIN, at);
                piglin.setPersistenceRequired();
                piglin.setNoAi(true);
                ((net.minecraft.world.entity.monster.piglin.AbstractPiglin) piglin).setImmuneToZombification(true);
            } else {
                final Villager v = helper.spawn(EntityTypes.VILLAGER, at);
                v.setPersistenceRequired();
                villagers.add(v);
            }
        }
        final List<EntityType<?>> types = List.of(EntityTypes.PLAYER, EntityTypes.VILLAGER, EntityTypes.PIGLIN, EntityTypes.ZOMBIE);
        final double[] ranges = {1.0, 4.0, 16.0, 64.0, 1024.0};
        helper.runAfterDelay(45, () -> {
            final StringBuilder f = new StringBuilder();
            int searches = 0;
            int playerHits = 0;
            for (final Villager body : villagers) {
                final Optional<NearestVisibleLivingEntities> memory = body.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
                if (memory.isEmpty()) {
                    continue;
                }
                final NearestVisibleLivingEntities visible = memory.get();
                // alternate the types so the same object is asked for several types in turn
                for (int round = 0; round < 2; round++) {
                    for (final EntityType<?> type : types) {
                        for (final double range : ranges) {
                            final Predicate<LivingEntity> filter = e -> e.is(type) && e.distanceToSqr(body) <= range;
                            final Optional<LivingEntity> slow = visible.findClosest(filter);
                            final Optional<LivingEntity> fast = visible.swift$findClosestOfType(type, filter);
                            searches++;
                            if (type == EntityTypes.PLAYER && fast.isPresent()) {
                                playerHits++;
                            }
                            if (!slow.equals(fast)) {
                                f.append("\n  around ").append(body.getId()).append(", ").append(type).append(" within ").append(range)
                                    .append(": full list ").append(slow.map(LivingEntity::getId)).append(", index ").append(fast.map(LivingEntity::getId));
                            }
                        }
                    }
                }
            }
            LOG.info("[SwiftGameTests] look-interact type index differential: {} searches, {} found a player", searches, playerHits);
            SwiftFakePlayer.leaveAll(helper);
            if (searches == 0) {
                f.append("\n  no villager had sensed its neighbours");
            }
            if (playerHits == 0) {
                f.append("\n  no search found a player: the match path was not tested");
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("type index differs:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * Optional: 150 crowded villagers and no player, the look-and-interact search as villagers run it (a fresh
     * sensor result every 20 ticks, a search every tick), full walk vs type index.
     */
    static void bench(final GameTestHelper helper) {
        final List<Villager> villagers = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            final Villager v = helper.spawn(EntityTypes.VILLAGER, new BlockPos(1 + i % 7, 1, 1 + (i / 7) % 7));
            v.setNoAi(true);
            villagers.add(v);
        }
        helper.runAfterDelay(5, () -> {
            final var level = helper.getLevel();
            final List<List<LivingEntity>> lists = new ArrayList<>();
            for (final Villager body : villagers) {
                final List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(16.0), e -> e != body && e.isAlive());
                io.papermc.paper.swift.entity.SwiftSort.byDistance(list, body);
                lists.add(list);
            }
            final boolean was = NearestVisibleLivingEntities.SWIFT_TYPE_INDEX;
            final long[][] rounds = new long[2][9];
            long found = 0;
            try {
                for (int round = -3; round < 9; round++) {
                    for (int variant = 0; variant < 2; variant++) {
                        final long t0 = System.nanoTime();
                        for (int i = 0; i < villagers.size(); i++) {
                            final Villager body = villagers.get(i);
                            final NearestVisibleLivingEntities visible = new NearestVisibleLivingEntities(level, body, lists.get(i));
                            final Predicate<LivingEntity> filter = e -> e.is(EntityTypes.PLAYER) && e.distanceToSqr(body) <= 16;
                            for (int tick = 0; tick < 20; tick++) {
                                found += (variant == 1 ? visible.swift$findClosestOfType(EntityTypes.PLAYER, filter) : visible.findClosest(filter)).isPresent() ? 1 : 0;
                            }
                        }
                        if (round >= 0) {
                            rounds[variant][round] = (System.nanoTime() - t0) / (20L * villagers.size());
                        }
                    }
                }
            } finally {
                NearestVisibleLivingEntities.SWIFT_TYPE_INDEX = was;
            }
            final long[] ns = new long[2];
            for (int v = 0; v < 2; v++) {
                java.util.Arrays.sort(rounds[v]);
                ns[v] = rounds[v][4];
            }
            LOG.info("[SwiftGameTests] look-interact search, 150 villagers x {} neighbours: full walk {} ns, type index {} ns per search, {}x ({} found)",
                lists.get(0).size(), ns[0], ns[1], String.format("%.2f", (double) ns[0] / Math.max(1, ns[1])), found);
            helper.succeed();
        });
    }

    /**
     * The real look-and-interact behaviour, run on a villager whose sensor saw a crowd and a player 1.5 blocks
     * away: it starts and picks the player, with the index on and off.
     */
    static void villagerTargetsNearbyPlayer(final GameTestHelper helper) {
        final SwiftFakePlayer player = SwiftFakePlayer.join(helper, "LookC", new BlockPos(4, 1, 6), false);
        player.player().setGameMode(GameType.SURVIVAL);
        final Villager villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(4, 1, 4));
        villager.setPersistenceRequired();
        // a crowd sorted in front of the player in the sensor's list, so the index has to skip them
        for (int i = 0; i < 12; i++) {
            final Villager v = helper.spawn(EntityTypes.VILLAGER, new BlockPos(1 + i % 3, 1, 1 + i / 3 % 3));
            v.setPersistenceRequired();
            v.setNoAi(true);
        }
        final var behavior = net.minecraft.world.entity.ai.behavior.SetLookAndInteract.create(EntityTypes.PLAYER, 4);
        helper.succeedWhen(() -> {
            player.player().teleportTo(villager.getX() + 1.5, villager.getY(), villager.getZ());
            final Brain<Villager> brain = villager.getBrain();
            final var visible = brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
            helper.assertTrue(visible.isPresent() && visible.get().nearbyEntities().contains(player.player()),
                Component.literal("sensor has not yet seen the player: " + visible.map(NearestVisibleLivingEntities::nearbyEntities)));
            final boolean was = NearestVisibleLivingEntities.SWIFT_TYPE_INDEX;
            final StringBuilder f = new StringBuilder();
            try {
                for (final boolean index : new boolean[]{true, false}) {
                    NearestVisibleLivingEntities.SWIFT_TYPE_INDEX = index;
                    brain.eraseMemory(MemoryModuleType.INTERACTION_TARGET);
                    brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
                    final boolean started = behavior.tryStart(helper.getLevel(), villager, helper.getLevel().getGameTime());
                    if (!started || !brain.isMemoryValue(MemoryModuleType.INTERACTION_TARGET, player.player())) {
                        f.append(" index ").append(index).append(": started ").append(started)
                            .append(", target ").append(brain.getMemory(MemoryModuleType.INTERACTION_TARGET)).append(';');
                    }
                    behavior.doStop(helper.getLevel(), villager, helper.getLevel().getGameTime());
                }
            } finally {
                NearestVisibleLivingEntities.SWIFT_TYPE_INDEX = was;
                brain.eraseMemory(MemoryModuleType.INTERACTION_TARGET);
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("look-and-interact did not pick the player:" + f));
            }
            SwiftFakePlayer.leaveAll(helper);
        });
    }
}
