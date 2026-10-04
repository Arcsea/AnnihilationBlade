package org.examplea.annihilationblade.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.examplea.annihilationblade.combat.TerminationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {
    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void annihilationblade$blockHealthRestore(float health, CallbackInfo callback) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (health > 0.0F && TerminationService.blocksHealthRestore(entity)) {
            callback.cancel();
        }
    }
}
