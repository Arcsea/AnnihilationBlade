package org.examplea.annihilationblade.combat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
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
 *
 * <p>1.21.1 的 loot API 与 1.20.1 不同：{@link LivingEntity#getLootTable()} 返回
 * {@link ResourceKey}（而非 {@code ResourceLocation}），需通过
 * {@code server.reloadableRegistries().getLootTable(key)} 获取；{@code KILLER_ENTITY} 参数
 * 已被 {@code ATTACKING_ENTITY} / {@code DIRECT_ATTACKING_ENTITY} 取代；
 * {@code dropExperience} 需要传入击杀者实体。</p>
 */
final class LootExtractor {
    private LootExtractor() {
    }

    static void drop(LivingEntity target, Player attacker, DamageSource source) {
        ServerLevel level = (ServerLevel) target.level();
        ResourceKey<LootTable> tableKey = target.getLootTable();
        if (tableKey != null) {
            LootTable table = level.getServer().reloadableRegistries().getLootTable(tableKey);
            LootParams.Builder builder = new LootParams.Builder(level)
                    .withParameter(LootContextParams.THIS_ENTITY, target)
                    .withParameter(LootContextParams.ORIGIN, target.position())
                    .withParameter(LootContextParams.DAMAGE_SOURCE, source)
                    .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, source.getEntity())
                    .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, source.getDirectEntity())
                    .withLuck(attacker != null ? attacker.getLuck() : 0.0F);
            LootParams params = builder.create(LootContextParamSets.ENTITY);
            for (ItemStack stack : table.getRandomItems(params)) {
                target.spawnAtLocation(stack);
            }
        }
        dropExperience(target, attacker);
    }

    /** 反射调用受保护的 dropExperience(Entity)，由 vanilla 计算经验值并生成经验球。 */
    private static void dropExperience(LivingEntity target, Player attacker) {
        if (!target.shouldDropExperience()) return;
        if (attacker == null) return;
        try {
            Method method = findMethod(target.getClass(), "dropExperience", Entity.class);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target, attacker);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?> param) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                return cursor.getDeclaredMethod(name, param);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
