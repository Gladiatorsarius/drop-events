package drop.events.swordsmp.mixin;

import drop.events.swordsmp.DropEventLinker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress only the guard's own vanilla loot, never nearby players' items or XP. */
@Mixin(LivingEntity.class)
public abstract class GuardLootMixin {
	@Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
	private void dropEvents$suppressGuardLoot(ServerLevel world, DamageSource source, CallbackInfo ci) {
		if ((Object) this instanceof Shulker shulker && shulker.getTags().contains(DropEventLinker.TAG_SHULKER)) {
			ci.cancel();
		}
	}
}
