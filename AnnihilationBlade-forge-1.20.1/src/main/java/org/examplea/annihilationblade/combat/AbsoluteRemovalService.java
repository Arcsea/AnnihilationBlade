package org.examplea.annihilationblade.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.examplea.annihilationblade.Annihilationblade;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class AbsoluteRemovalService {
    private static final String[] PART_METHODS = {"getParts", "getSubEntities", "getChildren"};

    private AbsoluteRemovalService() {
    }

    /** 第一级武器的移除结果：CLEARED 表示已清除，ESCALATE 表示需升级到核弹。 */
    enum Outcome { CLEARED, ESCALATE }

    static Outcome removeTree(LivingEntity root) {
        if (root instanceof Player && !TerminationService.nukePlayersEnabled()) return Outcome.ESCALATE;
        List<Entity> ordered = collectTree(root);

        boolean removedRoot = false;
        for (Entity entity : ordered) {
            if (entity instanceof Player && !TerminationService.nukePlayersEnabled()) continue;
            try {
                entity.stopRiding();
                entity.ejectPassengers();
                entity.setRemoved(Entity.RemovalReason.DISCARDED);
                if (entity == root) removedRoot = entity.isRemoved();
            } catch (Throwable throwable) {
                if (entity == root) {
                    Annihilationblade.LOGGER.warn("Absolute removal threw for {} ({})",
                            root.getClass().getName(), root.getUUID(), throwable);
                }
            }
        }
        if (removedRoot) return Outcome.CLEARED;
        Annihilationblade.LOGGER.warn("Absolute removal was rejected by {} ({})",
                root.getClass().getName(), root.getUUID());
        return Outcome.ESCALATE;
    }

    /** 收集实体整棵树（本体 + 乘客 + 反射发现的部件/子实体），供核弹驱逐与第一级移除共用。 */
    static List<Entity> collectTree(Entity root) {
        Set<Entity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Entity> ordered = new ArrayList<>();
        collect(root, visited, ordered, 0);
        Collections.reverse(ordered);
        return ordered;
    }

    private static void collect(Entity entity, Set<Entity> visited, List<Entity> ordered, int depth) {
        if (entity == null || depth > 8 || !visited.add(entity)) return;
        ordered.add(entity);
        for (Entity passenger : entity.getPassengers()) collect(passenger, visited, ordered, depth + 1);
        for (Entity part : reflectedParts(entity)) collect(part, visited, ordered, depth + 1);
    }

    private static List<Entity> reflectedParts(Entity entity) {
        for (String methodName : PART_METHODS) {
            Method method = findMethod(entity.getClass(), methodName);
            if (method == null || method.getParameterCount() != 0) continue;
            try {
                method.setAccessible(true);
                Object value = method.invoke(entity);
                List<Entity> result = entitiesFrom(value);
                if (!result.isEmpty()) return result;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return List.of();
    }

    private static List<Entity> entitiesFrom(Object value) {
        List<Entity> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) if (element instanceof Entity entity) result.add(entity);
        } else if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                Object element = Array.get(value, index);
                if (element instanceof Entity entity) result.add(entity);
            }
        }
        return result;
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
}
