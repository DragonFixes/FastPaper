package io.papermc.paper.fastpaper.playerdata;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.monster.warden.WardenSpawnTracker;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.Waypoint;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.slf4j.Logger;

/**
 * Applies NBT written by {@code /data} and {@code /execute store} to an online player.
 *
 * <p>Vanilla refuses to load NBT into a player, because {@code Entity.load} would reset
 * state the client and the server keep in sync. This class instead reads each supported
 * tag and sets it through the same setters gameplay uses, so the client is updated and
 * plugins see the usual events (teleports with cause {@code COMMAND}, potion effect
 * changes with cause {@code COMMAND}).
 *
 * <p>The set of supported tags follows the Modify Player Data mod by eclipseisoffline
 * (<a href="https://github.com/eclipseisoffline/modifyplayerdata">github.com/eclipseisoffline/modifyplayerdata</a>, LGPLv3).
 * Tags outside that set ({@code UUID}, {@code Dimension}, {@code playerGameType},
 * {@code XpLevel} and similar) are ignored; {@code /tp}, {@code /gamemode} and {@code /xp}
 * already cover them.
 *
 * <p>The data commands always write back the whole player tag, so only the keys whose
 * value differs from the player's current tag are applied. Without that, every edit would
 * also re-teleport the player, reload the inventory and reset the attributes.
 */
public final class PlayerDataEditor {

    /**
     * List-like tags that the player tag leaves out when they are empty. Removing one of
     * these with {@code /data remove} means "make it empty", so a missing key is treated
     * as an empty value instead of "unchanged".
     */
    private static final List<String> OMITTED_WHEN_EMPTY = List.of("Tags", "data", "active_effects");

    private PlayerDataEditor() {
    }

    public static void apply(final ServerPlayer player, final CompoundTag newTag, final Logger logger) {
        final CompoundTag oldTag = NbtPredicate.getEntityTagToCompare(player);
        final CompoundTag changed = new CompoundTag();
        for (final String key : newTag.keySet()) {
            final Tag value = newTag.get(key);
            if (!value.equals(oldTag.get(key))) {
                changed.put(key, value);
            }
        }
        for (final String key : OMITTED_WHEN_EMPTY) {
            if (oldTag.contains(key) && !newTag.contains(key)) {
                changed.put(key, key.equals("data") ? new CompoundTag() : new ListTag());
            }
        }
        if (changed.isEmpty()) {
            return;
        }

        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), logger)) {
            apply(player, TagValueInput.create(reporter, player.registryAccess(), changed));
        }
    }

    private static void apply(final ServerPlayer player, final ValueInput input) {
        applyEntity(player, input);
        applyLivingEntity(player, input);
        applyPlayer(player, input);
        applyServerPlayer(player, input);
    }

    private static void applyEntity(final ServerPlayer player, final ValueInput input) {
        input.read("Pos", Vec3.CODEC).ifPresent(pos -> player.teleportTo(
            player.level(), pos.x, pos.y, pos.z, Set.of(), player.getYRot(), player.getXRot(), false,
            PlayerTeleportEvent.TeleportCause.COMMAND
        ));
        input.read("Rotation", Vec2.CODEC).ifPresent(rotation -> player.teleportTo(
            player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), rotation.x, rotation.y, false,
            PlayerTeleportEvent.TeleportCause.COMMAND
        ));
        input.read("Motion", Vec3.CODEC).ifPresent(motion -> {
            player.setDeltaMovement(motion);
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
        });

        readDouble(input, "fall_distance").ifPresent(distance -> player.fallDistance = distance);
        readShort(input, "Fire").ifPresent(player::setRemainingFireTicks);
        input.getInt("Air").ifPresent(player::setAirSupply);
        readBoolean(input, "Invulnerable").ifPresent(player::setPermanentlyInvulnerable);
        input.getInt("PortalCooldown").ifPresent(player::setPortalCooldown);
        input.getInt("invulnerable_time").ifPresent(player::setInvulnerableTime);
        readBoolean(input, "Silent").ifPresent(player::setSilent);
        readBoolean(input, "NoGravity").ifPresent(player::setNoGravity);
        readBoolean(input, "Glowing").ifPresent(player::setGlowingTag);
        input.getInt("TicksFrozen").ifPresent(player::setTicksFrozen);
        // Paper keeps visual fire as a tri-state the fire flag is derived from every tick;
        // "false" means no override, matching how HasVisualFire is loaded from disk.
        readBoolean(input, "HasVisualFire").ifPresent(visualFire -> player.visualFire =
            visualFire ? net.kyori.adventure.util.TriState.TRUE : net.kyori.adventure.util.TriState.NOT_SET);

        input.list("Tags", Codec.STRING).ifPresent(list -> {
            final Set<String> tags = new HashSet<>();
            list.forEach(tags::add);
            for (final String current : List.copyOf(player.entityTags())) {
                if (!tags.contains(current)) {
                    player.removeTag(current);
                }
            }
            tags.forEach(player::addTag);
        });

        input.read("data", CustomData.CODEC).ifPresent(data -> player.setComponent(DataComponents.CUSTOM_DATA, data));
    }

    private static void applyLivingEntity(final ServerPlayer player, final ValueInput input) {
        input.read("attributes", AttributeInstance.Packed.LIST_CODEC).ifPresent(packed -> {
            // The tag holds base values and permanent modifiers only. Transient modifiers
            // (armor, held items, effects) are left alone; every attribute missing from the
            // tag goes back to its default.
            for (final AttributeInstance instance : List.copyOf(player.getAttributes().attributes.values())) {
                for (final AttributeModifier modifier : instance.getPermanentModifiers()) {
                    instance.removeModifier(modifier.id());
                }
                player.getAttributes().resetBaseValue(instance.getAttribute());
            }
            player.getAttributes().apply(packed);
        });
        readFloat(input, "Health").ifPresent(player::setHealth);
        readShort(input, "HurtTime").ifPresent(hurtTime -> {
            player.hurtTime = hurtTime;
            player.hurtDuration = hurtTime;
        });
        readFloat(input, "AbsorptionAmount").ifPresent(player::setAbsorptionAmount);
        input.getInt("current_impulse_context_reset_grace_time").ifPresent(time -> player.currentImpulseContextResetGraceTime = time);
        input.read("current_explosion_impact_pos", Vec3.CODEC).ifPresent(pos -> player.currentImpulseImpactPos = pos);

        input.read("active_effects", MobEffectInstance.CODEC.listOf()).ifPresent(effects -> {
            // Through removeAllEffects/addEffect so effect attribute modifiers (speed,
            // strength, health boost) are removed and applied with the effects.
            player.removeAllEffects(EntityPotionEffectEvent.Cause.COMMAND);
            for (final MobEffectInstance effect : effects) {
                player.addEffect(effect, EntityPotionEffectEvent.Cause.COMMAND);
            }
        });

        readBoolean(input, "FallFlying").ifPresent(fallFlying -> {
            if (fallFlying) {
                player.startFallFlying();
            } else {
                player.stopFallFlying();
            }
        });

        final EntityReference<Player> attackingPlayer = EntityReference.read(input, "last_hurt_by_player");
        final int playerMemory = input.getIntOr("last_hurt_by_player_memory_time", player.lastHurtByPlayerMemoryTime);
        if (attackingPlayer != null) {
            player.setLastHurtByPlayer(attackingPlayer.getUUID(), playerMemory);
        } else if (input.getInt("last_hurt_by_player_memory_time").isPresent()) {
            player.lastHurtByPlayerMemoryTime = playerMemory;
        }

        final EntityReference<LivingEntity> attacker = EntityReference.read(input, "last_hurt_by_mob");
        if (attacker != null) {
            player.lastHurtByMob = attacker;
        }
        input.getInt("ticks_since_last_hurt_by_mob").ifPresent(ticks -> player.lastHurtByMobTimestamp = player.tickCount - ticks);

        input.read("equipment", EntityEquipment.CODEC).ifPresent(player.equipment::setAll);
        input.read("locator_bar_icon", Waypoint.Icon.CODEC).ifPresent(icon -> player.waypointIcon().copyFrom(icon));
    }

    private static void applyPlayer(final ServerPlayer player, final ValueInput input) {
        final Inventory inventory = player.getInventory();
        input.list("Inventory", ItemStackWithSlot.CODEC).ifPresent(inventory::load);
        // SelectedItem is not a saved tag; the data commands add it to the player tag for
        // convenience, so it is accepted here as the item in the selected hotbar slot.
        input.read("SelectedItem", ItemStack.CODEC).ifPresent(stack -> inventory.setItem(inventory.getSelectedSlot(), stack));
        input.getInt("SelectedItemSlot").ifPresent(slot -> {
            if (Inventory.isHotbarSlot(slot)) {
                inventory.setSelectedSlot(slot);
                player.connection.send(new ClientboundSetHeldSlotPacket(slot));
            }
        });

        readShort(input, "SleepTimer").ifPresent(timer -> player.sleepCounter = timer);
        input.getInt("XpSeed").ifPresent(seed -> player.enchantmentSeed = seed == 0 ? player.getRandom().nextInt() : seed);
        input.getInt("Score").ifPresent(player::setScore);

        input.getInt("foodLevel").ifPresent(player.getFoodData()::setFoodLevel);
        input.getInt("foodTickTimer").ifPresent(timer -> player.getFoodData().tickTimer = timer);
        readFloat(input, "foodSaturationLevel").ifPresent(player.getFoodData()::setSaturation);
        readFloat(input, "foodExhaustionLevel").ifPresent(level -> player.getFoodData().exhaustionLevel = level);

        input.read("abilities", Abilities.Packed.CODEC).ifPresent(packed -> {
            player.getAbilities().apply(packed);
            player.onUpdateAbilities();
        });

        input.list("EnderItems", ItemStackWithSlot.CODEC).ifPresent(player.getEnderChestInventory()::fromSlots);
    }

    private static void applyServerPlayer(final ServerPlayer player, final ValueInput input) {
        input.read("warden_spawn_tracker", WardenSpawnTracker.CODEC).ifPresent(tracker -> player.wardenSpawnTracker = tracker);
        readBoolean(input, "seenCredits").ifPresent(seen -> player.seenCredits = seen);
        input.read("entered_nether_pos", Vec3.CODEC).ifPresent(pos -> player.enteredNetherPosition = pos);

        input.read("recipeBook", ServerRecipeBook.Packed.CODEC).ifPresent(packed -> {
            final ServerRecipeBook book = player.getRecipeBook();
            final List<RecipeHolder<?>> known = new ArrayList<>();
            for (final ResourceKey<Recipe<?>> id : book.known) {
                // removeRecipes only reads the id of each holder.
                known.add(new RecipeHolder<>(id, null));
            }
            book.removeRecipes(known, player);
            book.loadUntrusted(packed, id -> player.level().getServer().getRecipeManager().byKey(id).isPresent());
            book.sendInitialRecipeBook(player);
        });

        input.read("respawn", ServerPlayer.RespawnConfig.CODEC).ifPresent(respawn -> player.setRespawnPosition(respawn, false));
        readBoolean(input, "spawn_extra_particles_on_fall").ifPresent(player::setSpawnExtraParticlesOnFall);
        input.read("raid_omen_position", BlockPos.CODEC).ifPresent(player::setRaidOmenPosition);
        input.read("post_effects", Identifier.CODEC.listOf()).ifPresent(effects -> {
            player.clearPostEffects();
            effects.forEach(player::addPostEffect);
        });
    }

    private static Optional<Double> readDouble(final ValueInput input, final String key) {
        return input.read(key, Codec.DOUBLE);
    }

    private static Optional<Float> readFloat(final ValueInput input, final String key) {
        return input.read(key, Codec.FLOAT);
    }

    private static Optional<Short> readShort(final ValueInput input, final String key) {
        return input.read(key, Codec.SHORT);
    }

    private static Optional<Boolean> readBoolean(final ValueInput input, final String key) {
        return input.read(key, Codec.BOOL);
    }
}
