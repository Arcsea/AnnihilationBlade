package org.examplea.annihilationblade.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class TerminusLogic {

    private TerminusLogic() {
    }

    public static void execute(LivingEntity target, Player attacker) {
        TerminationService.request(target, attacker, TerminationContext.SPATIAL_FRACTURE);
    }
}
