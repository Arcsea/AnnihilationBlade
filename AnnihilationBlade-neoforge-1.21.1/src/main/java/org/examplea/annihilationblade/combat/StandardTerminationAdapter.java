package org.examplea.annihilationblade.combat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

final class StandardTerminationAdapter implements TerminationAdapter {
    @Override
    public String id() {
        return "standard";
    }

    @Override
    public boolean matches(LivingEntity target) {
        return true;
    }

    @Override
    public Outcome begin(LivingEntity target, Player attacker) {
        DamageSource source = target.level().damageSources().playerAttack(attacker);
        target.invulnerableTime = 0;
        target.setLastHurtByPlayer(attacker);
        TerminationService.runInternal(() -> {
            target.hurt(source, Float.MAX_VALUE);
            if (target.getHealth() > 0.0F) target.setHealth(0.0F);
            if (!target.isDeadOrDying()) target.die(source);
        });
        return DeathAcceptanceProbe.accepted(target) ? Outcome.ACCEPTED : Outcome.REJECTED;
    }

    @Override
    public void maintain(LivingEntity target) {
        // RUNNING 期间持续压制：Boss 若回血/复活，立即把血打回 0。
        if (target.getHealth() > 0.0F) target.setHealth(0.0F);
    }
}
