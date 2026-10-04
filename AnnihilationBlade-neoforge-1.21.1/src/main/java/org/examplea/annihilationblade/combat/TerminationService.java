package org.examplea.annihilationblade.combat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.examplea.annihilationblade.Annihilationblade;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TerminationService {
    private static final long TOMBSTONE_LIFETIME = 20L * 60L;
    // Max ticks to wait for a native death; escalate to the forced pipeline when exceeded (configurable).
    private static final long RUNNING_DEADLINE_TICKS = Math.max(1, Integer.getInteger("annihilationblade.deadlineTicks", 40));
    // Same-type successors are quarantined only right next to the removal site and only for
    // a short window, so spawn eggs / natural spawning keep working after a kill.
    private static final long REVIVAL_WINDOW_TICKS = Math.max(1, Integer.getInteger("annihilationblade.revivalWindowTicks", 40));
    private static final double REVIVAL_RADIUS = Math.max(1.0D, Double.parseDouble(System.getProperty("annihilationblade.revivalRadius", "16.0")));
    private static final ThreadLocal<Boolean> INTERNAL_DAMAGE = ThreadLocal.withInitial(() -> false);
    // 核弹移除后每 tick 重广播移除包所需的运行时实体 ID 缓存（UUID -> entityId），
    // 墓碑过期时清除，避免 Map 无限增长。
    private static final ConcurrentMap<UUID, Integer> LAST_KNOWN_IDS = new ConcurrentHashMap<>();
    private static final long NUCLEAR_BROADCAST_TICKS = 200;
    private static final List<TerminationAdapter> ADAPTERS =
            List.of(new LedgerTerminationAdapter());

    private TerminationService() {
    }

    static void recordKnownId(UUID uuid, int entityId) {
        LAST_KNOWN_IDS.put(uuid, entityId);
    }

    static void forgetKnownId(UUID uuid) {
        LAST_KNOWN_IDS.remove(uuid);
    }

    /**
     * 请求终结。命中实体可能是多部件 Boss 的部件/附属实体，先经
     * {@link TargetResolver} 解析回本体，再执行完整杀伤链路。
     *
     * @return {@code true} 表示本次命中已成功终结目标（原生死亡 / 强制清除 / 核弹湮灭）。
     */
    public static boolean request(Entity hitTarget, Player attacker, TerminationContext context) {
        if (hitTarget == null || attacker == null) return false;
        if (hitTarget.level().isClientSide || !(hitTarget.level() instanceof ServerLevel level)) return false;

        LivingEntity target = TargetResolver.resolve(hitTarget);
        if (target == null) return false;

        TerminationAdapter adapter = ADAPTERS.stream().filter(candidate -> candidate.matches(target)).findFirst().orElseThrow();
        TerminationSavedData data = TerminationSavedData.get(level);
        TerminationSavedData.Entry existing = data.get(target.getUUID());

        if (existing != null && existing.phase() != TerminationSavedData.Phase.FAILED) {
            // RUNNING/REQUESTED/SUCCEEDED/FORCE_CLEARED 且实体还活着：
            // 原生死亡曾被接受但没死成（Boss 复活 / die() 被取消 / 被重新召唤），
            // 直接升级到强制移除管线，而不是干等 60 秒墓碑过期。
            if (existing.phase() != TerminationSavedData.Phase.NUCLEAR_REMOVED && isPresentInLevel(level, target)) {
                return escalate(target, attacker, data, existing, level.getGameTime());
            }
            return false;
        }
        if (existing != null) data.remove(target.getUUID());

        long now = level.getGameTime();
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        TerminationSavedData.Entry request = new TerminationSavedData.Entry(
                UUID.randomUUID(), hitTarget.getUUID(), target.getUUID(), attacker.getUUID(), context,
                typeId, adapter.id(), TerminationSavedData.Phase.REQUESTED, now, now + RUNNING_DEADLINE_TICKS,
                now + TOMBSTONE_LIFETIME, now, "", false, 0.0D, 0.0D, 0.0D);
        if (!data.add(request)) return false;

        TerminationAdapter.Outcome outcome;
        try {
            outcome = adapter.begin(target, attacker);
        } catch (RuntimeException exception) {
            Annihilationblade.LOGGER.warn("Termination {} adapter {} threw for {}",
                    request.requestId(), adapter.id(), target.getClass().getName(), exception);
            outcome = TerminationAdapter.Outcome.REJECTED;
        }

        if (outcome == TerminationAdapter.Outcome.ACCEPTED) {
            data.update(request.withPhase(TerminationSavedData.Phase.RUNNING, now, "native death accepted"));
            Annihilationblade.LOGGER.info("Termination {} accepted: source={}, hit={}, root={}, adapter={}",
                    request.requestId(), context, hitTarget.getUUID(), target.getUUID(), adapter.id());
            return true;
        }

        return runForcedPipeline(target, attacker, data, request, now, context, hitTarget);
    }

    /** 兼容旧入口：直接传入 LivingEntity 命中实体。 */
    public static boolean request(LivingEntity hitTarget, Player attacker, TerminationContext context) {
        return request((Entity) hitTarget, attacker, context);
    }

    /** 墓碑存在但实体还活着时的升级路径：跳过适配器，直接走强制移除管线（战利品只掉一次）。 */
    private static boolean escalate(LivingEntity target, Player attacker, TerminationSavedData data,
                                    TerminationSavedData.Entry entry, long now) {
        Annihilationblade.LOGGER.warn("Termination {} escalating to force-removal: {} ({}) still alive after {}",
                entry.requestId(), target.getClass().getName(), target.getUUID(), entry.phase());
        return runForcedPipeline(target, attacker, data, entry, now, entry.context(), target);
    }

    /**
     * 强制移除管线：① 战利品提取（仅一次）→ ② 第一级 setRemoved → ③ 第二级核弹字段湮灭。
     * 任何一步抛异常都不会阻断后续击杀：战利品失败只记日志，移除手段逐级降级。
     */
    private static boolean runForcedPipeline(LivingEntity target, Player attacker, TerminationSavedData data,
                                             TerminationSavedData.Entry request, long now,
                                             TerminationContext context, Entity hitTarget) {
        DamageSource source = attacker != null
                ? target.level().damageSources().playerAttack(attacker)
                : target.level().damageSources().generic();

        if (!request.lootDropped()) {
            try {
                LootExtractor.drop(target, attacker, source);
                request = request.withLootDropped(true);
                data.update(request);
            } catch (Throwable throwable) {
                Annihilationblade.LOGGER.warn("Termination {} loot extraction failed for {} ({})",
                        request.requestId(), target.getClass().getName(), target.getUUID(), throwable);
            }
        }


        try {
            AbsoluteRemovalService.Outcome firstLevel = AbsoluteRemovalService.removeTree(target);
            if (firstLevel == AbsoluteRemovalService.Outcome.CLEARED) {
                data.update(request.withPhase(TerminationSavedData.Phase.FORCE_CLEARED, now, "absolute removal cleared")
                    .withRemovalPosition(target.getX(), target.getY(), target.getZ()));
                Annihilationblade.LOGGER.info("Termination {} force-cleared: source={}, hit={}, root={}, adapter={}",
                        request.requestId(), context, hitTarget.getUUID(), target.getUUID(), request.adapterId());
                return true;
            }
        } catch (Throwable throwable) {
            Annihilationblade.LOGGER.warn("Termination {} absolute removal failed for {} ({})",
                    request.requestId(), target.getClass().getName(), target.getUUID(), throwable);
        }

        try {
            boolean nuked = NuclearRemovalService.annihilate(target, attacker, source, data, request, now);
            if (nuked) {
                Annihilationblade.LOGGER.warn("Termination {} nuclear-removed: source={}, hit={}, root={}, adapter={}",
                        request.requestId(), context, hitTarget.getUUID(), target.getUUID(), request.adapterId());
                return true;
            }
        } catch (Throwable throwable) {
            Annihilationblade.LOGGER.error("Termination {} nuclear removal threw for {} ({})",
                    request.requestId(), target.getClass().getName(), target.getUUID(), throwable);
            data.update(request.withPhase(TerminationSavedData.Phase.FAILED, now,
                    "nuclear removal threw: " + throwable.getClass().getSimpleName()));
            return false;
        }

        Annihilationblade.LOGGER.warn("Termination {} failed: hit={}, root={}, adapter={}",
                request.requestId(), hitTarget.getUUID(), target.getUUID(), request.adapterId());
        return false;
    }

    /** Authoritative presence check: not relying on overridable isAlive()/isRemoved(). */
    private static boolean isPresentInLevel(ServerLevel level, LivingEntity target) {
        return target != null && level.getEntity(target.getUUID()) != null
                && !NuclearAccessor.fieldRemoved(target);
    }

    public static boolean isInternalDamage() {
        return INTERNAL_DAMAGE.get();
    }

    public static boolean isNuclearTarget(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        TerminationSavedData.Entry entry = TerminationSavedData.get(level).get(entity.getUUID());
        if (entry == null) return false;
        return entry.phase() == TerminationSavedData.Phase.FORCE_CLEARED
                || entry.phase() == TerminationSavedData.Phase.NUCLEAR_REMOVED;
    }

    public static boolean blocksHealthRestore(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        TerminationSavedData.Entry entry = TerminationSavedData.get(level).get(entity.getUUID());
        if (entry == null) return false;
        return entry.phase() == TerminationSavedData.Phase.RUNNING
                || entry.phase() == TerminationSavedData.Phase.FORCE_CLEARED
                || entry.phase() == TerminationSavedData.Phase.NUCLEAR_REMOVED;
    }

    public static boolean blocksRegistration(Entity entity) {
        if (entity instanceof Player || !(entity.level() instanceof ServerLevel level)) return false;
        // 实体自身已带核弹移除标记（字段直接写入，绕过任何可重写方法）：视为已死亡的实体
        // 被重新注册，直接拦下，由 MixinServerLevel 执行深度驱逐。
        if (NuclearAccessor.fieldRemoved(entity)) {
            Annihilationblade.LOGGER.warn("Blocking re-registration of field-removed entity {} ({}) id={}",
                    entity.getClass().getName(), entity.getUUID(), entity.getId());
            return true;
        }
        TerminationSavedData data = TerminationSavedData.get(level);
        TerminationSavedData.Entry entry = data.get(entity.getUUID());
        if (entry != null && (entry.phase() == TerminationSavedData.Phase.FORCE_CLEARED
                || entry.phase() == TerminationSavedData.Phase.NUCLEAR_REMOVED)) {
            return true;
        }
        // Bounded successor quarantine: block same-type entities that appear right next to the
        // removal site within a short window (in-place revival chains). Deliberate spawns -
        // spawn eggs, natural spawning, /summon - stay allowed anywhere else or later on.
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        long gameTime = level.getGameTime();
        for (TerminationSavedData.Entry candidate : data.snapshot()) {
            if (candidate.phase() != TerminationSavedData.Phase.FORCE_CLEARED
                    && candidate.phase() != TerminationSavedData.Phase.NUCLEAR_REMOVED) continue;
            if (!candidate.typeId().isEmpty() && !candidate.typeId().equals(typeId)) continue;
            if (gameTime - candidate.lastProgressAt() > REVIVAL_WINDOW_TICKS) continue;
            if (distanceToRemoval(entity, candidate) > REVIVAL_RADIUS) continue;
            Annihilationblade.LOGGER.warn("Quarantining {} ({}) as in-place successor of tombstone {}",
                    entity.getClass().getName(), entity.getUUID(), candidate.requestId());
            return true;
        }
        return false;
    }

    private static double distanceToRemoval(Entity entity, TerminationSavedData.Entry entry) {
        double dx = entity.getX() - entry.removedX();
        double dy = entity.getY() - entry.removedY();
        double dz = entity.getZ() - entry.removedZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 是否允许对 Player 派生实体（如基于 Player 的 NPC Boss）执行核弹级强制移除。默认关闭。 */
    public static boolean nukePlayersEnabled() {
        return Boolean.parseBoolean(System.getProperty("annihilationblade.nukePlayers", "false"));
    }

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        TerminationSavedData data = TerminationSavedData.get(level);
        for (TerminationSavedData.Entry request : data.snapshot()) {
            if (request.phase() == TerminationSavedData.Phase.RUNNING) {
                Entity entity = level.getEntity(request.targetId());
                if (entity == null || entity.isRemoved() || NuclearAccessor.fieldRemoved(entity)) {
                    data.update(request.withPhase(TerminationSavedData.Phase.SUCCEEDED, now, "native removal completed"));
                } else if (entity instanceof LivingEntity living) {
                    if (now >= request.deadline()) {
                        // Native death timed out (scripted death / long animation / teleport-on-death):
                        // escalate to the forced removal pipeline without waiting for another player hit.
                        escalateFromTick(level, living, data, request, now);
                    } else {
                        findAdapter(request.adapterId()).maintain(living);
                    }
                }
            } else if (request.phase() == TerminationSavedData.Phase.FORCE_CLEARED) {
                // Cross-tick confirmation for level 1: if the target is still in the world next tick,
                // level 1 was a false positive - escalate to nuclear field annihilation.
                Entity entity = level.getEntity(request.targetId());
                // FORCE_CLEARED 后实体仍注册在世界中（无论移除标记是否已写入）都要升级到
                // 核弹驱逐，避免“血条已消但实体冻结残留”的中间态。
                if (entity instanceof LivingEntity living) {
                    nuclearFromTick(level, living, data, request, now);
                }
            } else if (request.phase() == TerminationSavedData.Phase.NUCLEAR_REMOVED) {
                // 持续压制：Boss 的 AI/召唤者可能每 tick 尝试复活或清除移除标记，
                // 只要实体还存在就重复写字段并标记区块未保存，直到墓碑过期。
                // 即使实体已不在 EntityLookup 中，也可能仍残留在 ChunkMap 追踪器 /
                // 客户端实体表里（静止残影），因此持续广播移除包直到墓碑过期。
                Integer cachedId = LAST_KNOWN_IDS.get(request.targetId());
                long age = now - request.lastProgressAt();
                if (age < TOMBSTONE_LIFETIME && cachedId != null) {
                    DeepEvictionService.broadcastRemoval(level, cachedId);
                    if (age % 100 == 0) {
                        Annihilationblade.LOGGER.warn("Nuclear re-broadcast remove for {} (entityId={}) players={}",
                                request.targetId(), cachedId, level.players().size());
                    }
                }
                // 追踪器残留：目标 Mod 可能通过直接调用 callbacks.onTrackingStart 重建追踪器
                // （只发 spawn 包、不写回 EntityLookup），此时 level.getEntity 永远查不到。
                if (cachedId != null && DeepEvictionService.trackerHas(level, cachedId)) {
                    Annihilationblade.LOGGER.warn("Nuclear tracker ghost detected for {} (entityId={}): removing tracker",
                            request.targetId(), cachedId);
                    DeepEvictionService.broadcastRemoval(level, cachedId);
                    DeepEvictionService.removeTrackerById(level, cachedId);
                }
                Entity entity = level.getEntity(request.targetId());
                if (entity instanceof NuclearAccessor accessor) {
                    try {
                        accessor.annihilationblade$forceRemove();
                        accessor.annihilationblade$evict();
                        // 整棵实体树一起持续压制，防止部件/附属实体残留。
                        // 深度驱逐：每 tick 持续把整树实体从所有世界实体表中摘除，直到墓碑过期。
                        DeepEvictionService.purgeTree(entity);
                        ((ServerLevel) entity.level()).getChunkAt(entity.blockPosition()).setUnsaved(true);
                    } catch (Throwable throwable) {
                        Annihilationblade.LOGGER.warn("Termination {} nuclear suppression threw for {} ({})",
                                request.requestId(), entity.getClass().getName(), request.targetId(), throwable);
                    }
                }
                // 复活残留兜底：同类型、带“死亡标记”（removalReason/血量<=0/canUpdate=false）且位于
                // 移除点附近的实体视为复活残留，持续清除；健康的新生成实体不带这些标记，不会被误杀。
                if (!request.typeId().isEmpty() && age < NUCLEAR_BROADCAST_TICKS && age % 5 == 0) {
                    DeepEvictionService.sweep(level, request.removedX(), request.removedY(), request.removedZ(), request.typeId());
                    // 机制级 section 清剿：AABB 查询走 section 表，摘除躺在“小区”里
                    // （EntityLookup 查不到）的同类型死亡残留实体（碰撞箱残留的根源）。
                    DeepEvictionService.sweepSections(level, request.removedX(), request.removedY(), request.removedZ(),
                            REVIVAL_RADIUS, request.typeId());
                    for (Entity candidate : level.getEntities().getAll()) {
                        if (candidate == null || candidate instanceof Player) continue;
                        if (!BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType()).toString().equals(request.typeId())) continue;
                        if (candidate.distanceToSqr(request.removedX(), request.removedY(), request.removedZ()) > REVIVAL_RADIUS * REVIVAL_RADIUS) continue;
                        if (!DeepEvictionService.hasDeadMarker(candidate)) continue;
                        Annihilationblade.LOGGER.warn("Nuclear revival corpse purged for tombstone {}: {} ({}) at ({},{},{})",
                                request.requestId(), candidate.getClass().getName(), candidate.getUUID(),
                                candidate.getX(), candidate.getY(), candidate.getZ());
                        if (candidate instanceof NuclearAccessor candidateAccessor) {
                            candidateAccessor.annihilationblade$forceRemove();
                            candidateAccessor.annihilationblade$evict();
                        }
                        DeepEvictionService.purgeTree(candidate);
                    }
                }
            }
            if (now >= request.expiresAt()) {
                data.remove(request.targetId());
                NuclearRemovalService.forget(request.targetId());
                forgetKnownId(request.targetId());
            }
        }
    }

    /** Deadline escalation from tick: the attacker may be offline, the pipeline must accept a null attacker. */
    private static void escalateFromTick(ServerLevel level, LivingEntity target, TerminationSavedData data,
                                         TerminationSavedData.Entry request, long now) {
        Player attacker = level.getPlayerByUUID(request.attackerId());
        Annihilationblade.LOGGER.warn("Termination {} native-death deadline exceeded for {} ({}); escalating",
                request.requestId(), target.getClass().getName(), request.targetId());
        runForcedPipeline(target, attacker, data, request, now, request.context(), target);
    }

    /** Nuclear escalation from tick when level 1 was not confirmed. */
    private static void nuclearFromTick(ServerLevel level, LivingEntity target, TerminationSavedData data,
                                        TerminationSavedData.Entry request, long now) {
        Player attacker = level.getPlayerByUUID(request.attackerId());
        DamageSource source = attacker != null
                ? target.level().damageSources().playerAttack(attacker)
                : target.level().damageSources().generic();
        try {
            boolean nuked = NuclearRemovalService.annihilate(target, attacker, source, data, request, now);
            if (nuked) {
                Annihilationblade.LOGGER.warn("Termination {} escalated to nuclear: {} ({})",
                        request.requestId(), target.getClass().getName(), request.targetId());
            }
        } catch (Throwable throwable) {
            Annihilationblade.LOGGER.error("Termination {} nuclear escalation threw for {} ({})",
                    request.requestId(), target.getClass().getName(), request.targetId(), throwable);
            data.update(request.withPhase(TerminationSavedData.Phase.FAILED, now,
                    "nuclear escalation threw: " + throwable.getClass().getSimpleName()));
        }
    }

    static void runInternal(Runnable action) {
        INTERNAL_DAMAGE.set(true);
        try {
            action.run();
        } finally {
            INTERNAL_DAMAGE.remove();
        }
    }

    private static TerminationAdapter findAdapter(String id) {
        return ADAPTERS.stream().filter(adapter -> adapter.id().equals(id)).findFirst()
                .orElse(ADAPTERS.get(ADAPTERS.size() - 1));
    }
}
