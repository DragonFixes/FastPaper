package io.papermc.paper.swift.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.OneShot;

/**
 * FastPaper - brain behaviours called through their class instead of the {@link BehaviorControl} interface.
 * <p>
 * A brain loop calls {@code getStatus}, {@code tryStart} and {@code tickOrStop} on dozens of behaviour
 * classes from the same call site. The JIT cannot inline a call site with that many receiver types and falls
 * back to an interface table lookup on every call ({@code itable stub} in profiles; about 15% of villager
 * tick time). Almost every behaviour is a {@link OneShot}, a {@link Behavior} or a {@link GateBehavior}, whose
 * versions of these methods are final (or, for {@code Behavior#getStatus}, never overridden). Testing the class
 * first turns the call into a direct one that the JIT inlines. Anything else still goes through the interface.
 * The methods called are exactly the ones the interface call reaches, so behaviour is unchanged.
 * Disable with {@code -Dswift.brainDirectDispatch=false}.
 */
public final class SwiftBehaviors {
    /** Not final so tests can compare both paths inside one server. */
    public static boolean enabled = Boolean.parseBoolean(System.getProperty("swift.brainDirectDispatch", "true"));

    private SwiftBehaviors() {
    }

    public static Behavior.Status status(final BehaviorControl<?> behavior) {
        if (enabled) {
            if (behavior instanceof OneShot<?> oneShot) {
                return oneShot.getStatus();
            }
            if (behavior instanceof Behavior<?> plain) {
                return plain.getStatus();
            }
            if (behavior instanceof GateBehavior<?> gate) {
                return gate.getStatus();
            }
        }
        return behavior.getStatus();
    }

    @SuppressWarnings("unchecked")
    public static <E extends LivingEntity> boolean tryStart(final BehaviorControl<? super E> behavior, final ServerLevel level, final E body, final long timestamp) {
        if (enabled) {
            if (behavior instanceof OneShot<?> oneShot) {
                return ((OneShot<? super E>) oneShot).tryStart(level, body, timestamp);
            }
            if (behavior instanceof Behavior<?> plain) {
                return ((Behavior<? super E>) plain).tryStart(level, body, timestamp);
            }
            if (behavior instanceof GateBehavior<?> gate) {
                return ((GateBehavior<? super E>) gate).tryStart(level, body, timestamp);
            }
        }
        return behavior.tryStart(level, body, timestamp);
    }

    @SuppressWarnings("unchecked")
    public static <E extends LivingEntity> void tickOrStop(final BehaviorControl<? super E> behavior, final ServerLevel level, final E body, final long timestamp) {
        if (enabled) {
            if (behavior instanceof OneShot<?> oneShot) {
                ((OneShot<? super E>) oneShot).tickOrStop(level, body, timestamp);
                return;
            }
            if (behavior instanceof Behavior<?> plain) {
                ((Behavior<? super E>) plain).tickOrStop(level, body, timestamp);
                return;
            }
            if (behavior instanceof GateBehavior<?> gate) {
                ((GateBehavior<? super E>) gate).tickOrStop(level, body, timestamp);
                return;
            }
        }
        behavior.tickOrStop(level, body, timestamp);
    }
}
