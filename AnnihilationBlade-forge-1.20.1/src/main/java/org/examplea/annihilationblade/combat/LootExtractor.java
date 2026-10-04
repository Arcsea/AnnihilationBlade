package org.examplea.annihilationblade.combat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.lang.reflect.Method;

/**
 * 读取目标实体的原版战利品表，自行计算并生成物品与经验球。
 *
 * <p>在进入强制移除管线前执行一次，无论后续使用哪一级武器移除实体，掉落行为完全一致。
 * 不走实体的 {@code die()} / {@code dropFromLootTable()}，避免被 Boss 重写拦截。</p>
 */
final class LootExtractor {
    private LootExtractor() {
    }

    static void drop(LivingEntity target, Player attacker, DamageSource source) {
        ServerLevel level = (ServerLevel) target.level();
        ResourceLocation tableId = target.getLootTable();
        if (tableId != null) {
            LootTable table = level.getServer().getLootData().getLootTable(tableId);
            LootParams.Builder builder = new LootParams.Builder(level)
                    .withParameter(LootContextParams.THIS_ENTITY, target)
                    .withParameter(LootContextParams.ORIGIN, target.position())
                    .withParameter(LootContextParams.DAMAGE_SOURCE, source)
                    .withLuck(attacker != null ? attacker.getLuck() : 0.0F);
            if (attacker != null) {
                builder.withParameter(LootContextParams.KILLER_ENTITY, attacker)
                        .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, attacker);
            }
            LootParams params = builder.create(LootContextParamSets.ENTITY);
            for (ItemStack stack : table.getRandomItems(params)) {
                target.spawnAtLocation(stack);
            }
        }
        dropExperience(target);
    }

    /** 反射调用受保护的 dropExperience()（无参），由 vanilla 计算经验值并生成经验球。 */
    private static void dropExperience(LivingEntity target) {
        if (!target.shouldDropExperience()) return;
        try {
            Method method = findNoArgMethod(target.getClass(), "dropExperience");
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                return cursor.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
