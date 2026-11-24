package org.dreeam.leaf.world;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Reusable scratch lists for {@code Entity#collide}. Entities of one world may tick on several threads at
 * once (parallel entity ticking), so every thread owns its own lists.
 */
public record EntityCollisionCache(
    ObjectArrayList<VoxelShape> potentialCollisionsVoxel,
    ObjectArrayList<AABB> potentialCollisionsBB,
    ObjectArrayList<AABB> entityAABBs
) {
    private static final ThreadLocal<EntityCollisionCache> LOCAL = ThreadLocal.withInitial(EntityCollisionCache::new);

    public EntityCollisionCache() {
        this(new ObjectArrayList<>(), new ObjectArrayList<>(), new ObjectArrayList<>());
    }

    /**
     * The calling thread's lists, emptied: a collision that threw leaves its entries behind, and they must not
     * reach the next one.
     */
    public static EntityCollisionCache acquire() {
        final EntityCollisionCache cache = LOCAL.get();
        cache.clear();
        return cache;
    }

    public void clear() {
        potentialCollisionsVoxel.clear();
        potentialCollisionsBB.clear();
        entityAABBs.clear();
    }
}
