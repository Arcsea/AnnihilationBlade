package org.examplea.annihilationblade.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.examplea.annihilationblade.combat.NuclearAccessor;
import org.examplea.annihilationblade.combat.TerminationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void annihilationblade$blockForceClearedEntity(
            Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (!TerminationService.blocksRegistration(entity)) return;
        if (entity instanceof NuclearAccessor accessor) {
            accessor.annihilationblade$forceRemove();
        }
        callback.setReturnValue(false);
    }
}
