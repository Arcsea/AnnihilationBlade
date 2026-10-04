package org.examplea.annihilationblade.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.examplea.annihilationblade.combat.NuclearAccessor;
import org.examplea.annihilationblade.combat.TerminationContext;
import org.examplea.annihilationblade.combat.TerminationService;
import org.examplea.annihilationblade.event.ModEventHandler;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 直接操作 {@link Entity} 底层私有字段，绕过目标实体重写的 {@code setRemoved / remove / hurt / die}。
 *
 * <p>核弹核心：所有 Boss 的防御手段都建立在「方法调用链」上做文章，唯一不可被 Mod 重写
 * 拦截的攻击面是字段写入。这里通过 {@code @Shadow} 直接持有
 * {@code Entity.removalReason}（Forge 1.20.1 与 NeoForge 1.21.1 字段名相同），
 * 写入 {@link Entity.RemovalReason#DISCARDED} 即可：</p>
 * <ul>
 *   <li>令 {@link Entity#isRemoved()} 永久返回 {@code true}；</li>
 *   <li>再调用 {@code EntityInLevelCallback.onRemove}（该回调属于世界实体表
 *       {@code PersistentEntitySectionManager}，不经过任何可重写的实体方法），
 *       直接从运行时实体表与区块 NBT 中摘除实体，实现持久化湮灭。</li>
 * </ul>
 *
 * <p>注：方案原文写 {@code @Shadow private boolean removed;}，但 1.20.1 / 1.21.1 的
 * {@code Entity} 已重构为 {@code RemovalReason removalReason}（无 {@code removed} 布尔字段），
 * {@code isRemoved()} 实现为 {@code return this.removalReason != null;}，此处按真实映射修正。</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntity implements NuclearAccessor {
    @Shadow
    @Nullable
    private Entity.RemovalReason removalReason;

    @Shadow
    @Nullable
    private EntityInLevelCallback levelCallback;

    /** 绕过一切 setRemoved / remove 重写，直接把底层 removalReason 置为 DISCARDED。 */
    @Override
    public void annihilationblade$forceRemove() {
        this.removalReason = Entity.RemovalReason.DISCARDED;
    }

    @Override
    public boolean annihilationblade$isFieldRemoved() {
        return this.removalReason != null;
    }

    /** 驱逐：调用实体所在世界的驱逐回调（PersistentEntitySectionManager 的唯一驱逐断点）。 */
    @Override
    public void annihilationblade$evict() {
        EntityInLevelCallback callback = this.levelCallback;
        if (callback != null && callback != EntityInLevelCallback.NULL) {
            callback.onRemove(Entity.RemovalReason.DISCARDED);
        }
    }
    @Override
    public EntityInLevelCallback annihilationblade$levelCallback() {
        return this.levelCallback;
    }

    @Override
    public void annihilationblade$nullCallback() {
        this.levelCallback = EntityInLevelCallback.NULL;
    }

    /** 墓碑标记 NUCLEAR_REMOVED 时强制 isRemoved() 返回 true，防止实体复活。 */
    @Inject(method = "isRemoved", at = @At("HEAD"), cancellable = true)
    private void annihilationblade$forceNuclearRemoved(CallbackInfoReturnable<Boolean> cir) {
        if (TerminationService.isNuclearTarget((Entity) (Object) this)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 伤害事件链级兜底触发：在 {@link Entity#hurt} 的 HEAD 拦截，位于一切 Boss 防御
     * （isInvulnerableTo / 重写 hurt / 取消 LivingHurtEvent / 非 LivingEntity 部件）之前。
     * 只要伤害来源是持有湮灭之刃的玩家，就直接尝试终结。
     */
    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"), cancellable = true)
    private void annihilationblade$killOnBladeHit(
            DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (TerminationService.isInternalDamage()) return;
        Entity target = (Entity) (Object) this;
        if (!(source.getEntity() instanceof Player player)) return;
        if (target == player) return;
        if (!ModEventHandler.shouldKill(player, source)) return;
        if (TerminationService.request(target, player, TerminationContext.NORMAL_ATTACK)) {
            cir.setReturnValue(false);
        }
    }
}
