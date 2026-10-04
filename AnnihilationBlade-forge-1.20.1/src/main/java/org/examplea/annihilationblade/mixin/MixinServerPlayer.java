package org.examplea.annihilationblade.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import org.examplea.annihilationblade.combat.TerminationContext;
import org.examplea.annihilationblade.combat.TerminationService;
import org.examplea.annihilationblade.event.ModEventHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 攻击动作级兜底触发：主手持有湮灭之刃时，玩家对任何实体的攻击意图
 * （无论该实体的伤害是否走原版 hurt / 事件链、是否无敌、是否是非 LivingEntity 部件、
 * 或使用完全自定义的战斗系统）都直接尝试终结。
 *
 * <p>与 {@link MixinEntity} 的 {@code hurt} HEAD 注入互为补充：
 * 前者覆盖一切「伤害真正落下来」的路径，这里覆盖「攻击动作已经发出但伤害根本不会
 * 经过 hurt()」的路径。墓碑去重保证同一命中不会重复处理。</p>
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer {
    @Inject(method = "attack(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void annihilationblade$killOnAttackIntent(Entity target, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (target == null || target == player) return;
        if (!ModEventHandler.isGodBlade(player.getMainHandItem())) return;
        if (TerminationService.request(target, player, TerminationContext.NORMAL_ATTACK)) {
            ci.cancel();
        }
    }
}
