package org.examplea.annihilationblade.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

interface TerminationAdapter {
    String id();

    boolean matches(LivingEntity target);

    Outcome begin(LivingEntity target, Player attacker);

    default void maintain(LivingEntity target) {
    }

    enum Outcome {
        ACCEPTED, REJECTED
    }
}
