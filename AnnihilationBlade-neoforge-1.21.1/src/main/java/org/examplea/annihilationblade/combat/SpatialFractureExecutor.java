package org.examplea.annihilationblade.combat;

import mods.flammpfeil.slashblade.util.AttackManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Set;
import org.examplea.annihilationblade.Annihilationblade;
import org.examplea.annihilationblade.event.ModEventHandler;

/**
 * Executes the Spatial Fracture slash arts by sweeping a More-Avaritia-inspired ray and wiping every valid target.
 */
public final class SpatialFractureExecutor {
    private static final double MAX_DISTANCE = 128.0D;

    private SpatialFractureExecutor() {
    }

    public static void unleash(LivingEntity entity) {
        if (!(entity instanceof Player player)) return;
        if (entity.level().isClientSide) return; // 1.21+ 规范的 level() 间接调用

        try {
            ServerLevel level = (ServerLevel) entity.level();
            Set<LivingEntity> targets = gatherTargets(level, player);
            if (targets.isEmpty()) {
                return;
            }

            targets.forEach(target -> {
                try {
                    spawnSlash(level, player, target);
                    TerminationService.request(target, player, TerminationContext.SPATIAL_FRACTURE);
                } catch (Throwable t) {
                    Annihilationblade.LOGGER.error("[AnnihilationBlade] Error executing SA on target: " + target, t);
                }
            });

            playDetonation(level, player, targets.size());
        } catch (Throwable t) {
            Annihilationblade.LOGGER.error("[AnnihilationBlade] Fatal error in SpatialFractureExecutor.unleash", t);
        }
    }

    private static Set<LivingEntity> gatherTargets(ServerLevel level, Player player) {
        // 以玩家为球心、半径 MAX_DISTANCE 的球形范围，收集范围内所有可击杀目标。
        AABB sphere = player.getBoundingBox().inflate(MAX_DISTANCE);
        Set<LivingEntity> targets = new LinkedHashSet<>();
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, sphere,
                entity -> canTarget(player, entity)
                        && player.distanceToSqr(entity) <= MAX_DISTANCE * MAX_DISTANCE)) {
            targets.add(candidate);
        }
        return targets;
    }

    private static boolean canTarget(Player player, LivingEntity candidate) {
        if (candidate == player) return false;
        if (!candidate.isAlive()) return false;
        if (candidate.isAlliedTo(player)) return false;
        if (candidate instanceof Player other) {
            if (other.isCreative() || other.isSpectator()) return false;
            return !ModEventHandler.isGodBlade(other.getMainHandItem());
        }
        return true;
    }

    private static void spawnSlash(ServerLevel level, Player player, LivingEntity target) {
        // 生成斩击中心的空间破裂粒子特效
        level.sendParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), 10, 0.6, 0.6, 0.6, 0.0D);
        level.sendParticles(ParticleTypes.PORTAL, target.getX(), target.getY() + 1.0D, target.getZ(), 20, 1.0D, 1.0D, 1.0D, 0.3D);

        AttackManager.doSlash(player, player.getRandom().nextInt(360), Vec3.ZERO, true, true, 9999.0F);
    }

    private static void playDetonation(ServerLevel level, Player player, int count) {
        float volume = Math.min(4.0F, 1.0F + count / 20.0F);
        // 播放引爆空间时的史诗音效
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, volume, 0.4F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, volume, 0.8F);

        // 释放终结冲击粒子
        level.sendParticles(ParticleTypes.DRAGON_BREATH, player.getX(), player.getY() + 1.0D, player.getZ(), 80, 2.0D, 1.0D, 2.0D, 0.2D);
    }
}
