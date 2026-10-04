package org.examplea.annihilationblade.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 把「命中的实体」解析回真正的本体，兼容任意 MOD 的多部件/附属实体结构：
 * <ul>
 *   <li>多部件 Boss 的部件（类名/接口含 part/segment/multipart/subentity 等，含原版
 *       {@code EnderDragonPart}、冰与火、龙之研究、Mowzie's 等）→ 沿 owner 链爬回本体；</li>
 *   <li>召唤物/附属活体（{@code getOwner} / {@code getParent} 指向的非玩家活体）→ 宿主；</li>
 *   <li>普通实体 → 自身；非 LivingEntity 且找不到宿主的实体 → {@code null}（调用方放弃）。</li>
 * </ul>
 * 解析失败返回 {@code null}，调用方跳过本次击杀，不影响正常伤害。
 */
final class TargetResolver {
    private static final int MAX_DEPTH = 6;

    private static final String[] OWNER_METHODS = {
            "getParent", "getOwner", "getTitan", "getPartOwner", "getMaster",
            "getParentMob", "getMainBody", "getRoot", "getPartParent"};
    private static final String[] OWNER_FIELDS = {
            "parent", "parentMob", "owner", "titan", "entityTitan",
            "master", "partOwner", "mainBody", "root", "parentEntity"};
    private static final String[] PART_NAME_HINTS = {
            "part", "segment", "multipart", "subentity", "bodypart", "fragment"};

    private TargetResolver() {
    }

    static LivingEntity resolve(Entity hit) {
        if (hit == null) return null;

        if (!(hit instanceof LivingEntity current)) {
            // 非 LivingEntity 部件（如原版 EnderDragonPart）：直接找宿主，宿主非活体则放弃
            Entity owner = findOwner(hit);
            return owner instanceof LivingEntity living ? living : null;
        }

        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Entity owner = findOwner(current);
            if (!(owner instanceof LivingEntity living) || living == current) break;
            if (living instanceof Player) break;   // 宠物/坐骑等 owner 是玩家：别爬回玩家
            if (!isPartLike(current)) {
                // 非部件活体带非玩家 owner（如召唤物→召唤者）：只有明显是附属物才继续爬
                if (!looksLikeAttached(current)) break;
            }
            current = living;
        }
        return current;
    }

    /** 类名/父类/接口命中部件特征词的实体视为部件。 */
    private static boolean isPartLike(Entity entity) {
        for (Class<?> cursor = entity.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
            String name = cursor.getName().toLowerCase(Locale.ROOT);
            if (matchesHint(name)) return true;
            for (Class<?> iface : cursor.getInterfaces()) {
                if (matchesHint(iface.getName().toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private static boolean matchesHint(String name) {
        for (String hint : PART_NAME_HINTS) {
            if (name.contains(hint)) return true;
        }
        return false;
    }

    /**
     * 非部件实体也带 owner 且不是玩家 → 视为附属（召唤物/身体部位），允许爬回宿主。
     * 该判定用于兜底那些部件类名不带特征词、但确实有宿主关系的 MOD 生物。
     */
    private static boolean looksLikeAttached(Entity entity) {
        return findOwner(entity) instanceof LivingEntity;
    }

    private static Entity findOwner(Entity entity) {
        for (String name : OWNER_METHODS) {
            try {
                Method method = findMethod(entity.getClass(), name);
                if (method != null && Entity.class.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    return (Entity) method.invoke(entity);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        for (String name : OWNER_FIELDS) {
            try {
                Field field = findField(entity.getClass(), name);
                if (field != null && Entity.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (Entity) field.get(entity);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                return cursor.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
