package org.examplea.annihilationblade.combat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.examplea.annihilationblade.Annihilationblade;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 核弹级湮灭：在 {@link AbsoluteRemovalService} 的 setRemoved 被 Boss 重写拒绝后启用。
 *
 * <p>不调用任何可被重写的方法（setRemoved/remove/hurt/die），而是通过
 * {@link NuclearAccessor#annihilationblade$forceRemove()} 直接写入
 * {@code Entity.removalReason = DISCARDED}，随后调用
 * {@link NuclearAccessor#annihilationblade$evict()} 触发世界实体表的驱逐回调
 * {@code EntityInLevelCallback.onRemove}（属于 {@code PersistentEntitySectionManager}，
 * 不经过任何可重写的实体方法，Mod 无法拦截），直接从运行时实体表与区块 NBT 中
 * 摘除实体，完成持久化湮灭。</p>
 *
 * <p>熔断计数带时间窗口（{@link #BREAKER_RESET_TICKS}）：窗口内超限才熔断，窗口过后自动重置，
 * 不再像旧实现那样对同一 UUID 永久放弃。</p>
 */
final class NuclearRemovalService {
    private static final int MAX_ATTEMPTS = Math.max(1, Integer.getInteger("annihilationblade.maxNukeAttempts", 3));
    private static final long BREAKER_RESET_TICKS = 20L * 60L * 5L; // 熔断窗口：5 分钟
    private static final ConcurrentMap<UUID, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> FIRST_ATTEMPT = new ConcurrentHashMap<>();

    private NuclearRemovalService() {
    }

    /** 墓碑清理时遗忘计数，避免静态 Map 无限增长。 */
    static void forget(UUID targetId) {
        ATTEMPTS.remove(targetId);
        FIRST_ATTEMPT.remove(targetId);
    }

    /**
     * @return {@code true} 已将实体核弹湮灭并标记 {@code NUCLEAR_REMOVED}；
     *         {@code false} 熔断放弃，墓碑已标记 {@code FAILED}。
     */
    static boolean annihilate(LivingEntity target, Player attacker, DamageSource source,
                              TerminationSavedData data, TerminationSavedData.Entry request, long now) {
        if (target instanceof Player && !TerminationService.nukePlayersEnabled()) return false;

        UUID id = target.getUUID();
        int attempts = ATTEMPTS.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
        long firstAt = FIRST_ATTEMPT.computeIfAbsent(id, key -> now);

        if (attempts > MAX_ATTEMPTS) {
            if (now - firstAt < BREAKER_RESET_TICKS) {
                Annihilationblade.LOGGER.error(
                        "Nuclear removal circuit-breaker tripped for {} ({}): attempts={}, cooling down until t={}",
                        target.getClass().getName(), id, attempts, firstAt + BREAKER_RESET_TICKS);
                data.update(request.withPhase(TerminationSavedData.Phase.FAILED, now,
                        "nuclear circuit-breaker tripped after " + attempts + " attempts"));
                return false;
            }
            // 熔断窗口已过：重置计数后按首次尝试处理
            ATTEMPTS.put(id, new AtomicInteger(1));
            FIRST_ATTEMPT.put(id, now);
        }

        try {
            NuclearAccessor accessor = (NuclearAccessor) target;
            try {
                EntityInLevelCallback callback = accessor.annihilationblade$levelCallback();
                Annihilationblade.LOGGER.warn("Nuclear pre-evict callback for {} ({}): {}",
                        target.getClass().getName(), id, callback == null ? "null" : callback.getClass().getName());
            } catch (Throwable ignored) {
            }
            accessor.annihilationblade$forceRemove();
            accessor.annihilationblade$evict();
            // 整棵实体树（部件 / 附属 / 乘客）一起驱逐，避免多部件 Boss 残留静止部件。
            for (Entity part : AbsoluteRemovalService.collectTree(target)) {
                if (part == target || part instanceof Player) continue;
                if (part instanceof NuclearAccessor partAccessor) {
                    partAccessor.annihilationblade$forceRemove();
                    partAccessor.annihilationblade$evict();
                }
            }
        } catch (Throwable throwable) {
            Annihilationblade.LOGGER.error("Nuclear forceRemove/evict threw for {} ({})",
                    target.getClass().getName(), id, throwable);
            // 不标记 FAILED：保持 NUCLEAR_REMOVED，让维护循环每 tick 持续重试驱逐。
        }

        // 驱逐后验证：若实体仍注册在世界实体表中，记录日志并靠维护循环继续压制。
        // 深度驱逐兜底：标准驱逐之后无论是否残留，都把整树实体从所有世界实体表中摘掉，
        // 并销毁追踪器广播移除包（清除客户端残影）。幂等，可安全重复执行。
        boolean deepPurged = DeepEvictionService.purgeTree(target);

        try {
            Entity stillPresent = ((ServerLevel) target.level()).getEntity(id);
            if (stillPresent != null) {
                Annihilationblade.LOGGER.error("Nuclear eviction did not take for {} ({}): still registered after deep purge, will retry",
                        target.getClass().getName(), id);
            } else if (!deepPurged) {
                Annihilationblade.LOGGER.warn("Nuclear eviction partially purged for {} ({})",
                        target.getClass().getName(), id);
            }
        } catch (Throwable ignored) {
        }

        // US Mod 专用兜底：核弹驱逐后若实体仍注册（或客户端仍持残影），再调用它自己
        // 的 Deathlist.killEntity 做完整摘除（含向客户端广播移除包），并复位 disableSpawn。

        // 标记所在区块未保存，触发 DISCARDED 实体在保存时不被写入区块 NBT。
        try {
            ((ServerLevel) target.level()).getChunkAt(target.blockPosition()).setUnsaved(true);
        } catch (Throwable ignored) {
            // 辅助手段，失败不影响核弹主效果（PersistentEntitySectionManager 仍会自动清理）。
        }

        TerminationService.recordKnownId(id, target.getId());

        data.update(request.withPhase(TerminationSavedData.Phase.NUCLEAR_REMOVED, now, "nuclear annihilated")
                .withRemovalPosition(target.getX(), target.getY(), target.getZ()));
        try {
            Annihilationblade.LOGGER.warn("Nuclear post-purge diagnostics for {} ({}): {}",
                    target.getClass().getName(), id, DeepEvictionService.diagnose((ServerLevel) target.level(), target));
        } catch (Throwable ignored) {
        }
        try {
            ServerLevel serverLevel = (ServerLevel) target.level();
            double tx = target.getX();
            double ty = target.getY();
            double tz = target.getZ();
            for (Entity other : serverLevel.getEntities().getAll()) {
                if (other == target || other.getClass() != target.getClass()) continue;
                if (other.distanceToSqr(tx, ty, tz) <= 16384.0D) {
                    Annihilationblade.LOGGER.warn("Nuclear leftover scan: same-type {} ({}) at ({},{},{}) near removal point",
                            other.getClass().getSimpleName(), other.getUUID(), other.getX(), other.getY(), other.getZ());
                }
            }
        } catch (Throwable ignored) {
        }

        Annihilationblade.LOGGER.warn("Nuclear removal annihilated {} ({}) in {} by {} (attempt {}/{})",
                target.getClass().getName(), id,
                target.level().dimension().location(),
                attacker != null ? attacker.getName().getString() : "?", attempts, MAX_ATTEMPTS);
        return true;
    }
}
