package drop.events.swordsmp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityHitboxAccessor {
    @Accessor("dimensions")
    void dropEvents$setDimensions(EntityDimensions dimensions);

    @Invoker("setBoundingBox")
    void dropEvents$setBoundingBox(AABB box);
}
