package org.examplea.annihilationblade.combat;

import net.minecraft.world.entity.LivingEntity;

final class DeathAcceptanceProbe {
    private DeathAcceptanceProbe() {
    }

    static boolean accepted(LivingEntity target) {
        // Authoritative check first: raw removalReason field written, bypassing overridable methods.
        if (NuclearAccessor.fieldRemoved(target)) return true;
        return target.isRemoved() || target.isDeadOrDying() || !target.isAlive();
    }
}
