
package org.examplea.annihilationblade.combat;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.phys.AABB;
import org.examplea.annihilationblade.Annihilationblade;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
final class DeepEvictionService {
    private static final ConcurrentMap<Class<?>, ConcurrentMap<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private DeepEvictionService() {
    }

    /** 整树深度驱逐（本体 + 乘客 + 反射发现的部件），返回是否已全部从世界实体表消失。 */
    /** 鏁存爲娣卞害椹遍€愶紙鏈€浣?+ 涔樺 + 鍙嶅皠鍙戠幇鐨勯儴浠讹級锛屽苟鍚戞墍鏈夌帺瀹跺箍鎾绉婚櫎鍖咃紙娓呴櫎瀹㈡埛绔弽褰憋級銆?*/
    static boolean purgeTree(Entity root) {
        if (root == null) return true;
        List<Entity> tree = AbsoluteRemovalService.collectTree(root);
        int[] ids = new int[tree.size()];
        for (int i = 0; i < tree.size(); i++) ids[i] = tree.get(i).getId();
        if (root.level() instanceof ServerLevel level) broadcastRemoval(level, ids);
        boolean allGone = true;
        for (Entity entity : tree) {
            if (!purge(entity)) allGone = false;
        }
        return allGone;
    }

    /** 深度驱逐单个实体；幂等，可重复调用（维护循环每 tick 调用也安全）。 */
    static boolean purge(Entity entity) {
        if (entity == null) return true;
        if (!(entity.level() instanceof ServerLevel level)) return true;
        try {
            if (entity instanceof NuclearAccessor accessor) {
                accessor.annihilationblade$forceRemove();
                accessor.annihilationblade$evict();
                removeFromSection(entity);
                accessor.annihilationblade$nullCallback();
            }
            removeFromTickList(level, entity);
            removeFromLookup(level, entity);
            removeKnownUuid(level, entity);
            removeTracker(level, entity);
            removeMiscLists(level, entity);
            removedFromWorldHook(entity);
            level.getChunkAt(entity.blockPosition()).setUnsaved(true);
        } catch (Throwable throwable) {
            Annihilationblade.LOGGER.error("Deep eviction threw for {} ({})",
                    entity.getClass().getName(), entity.getUUID(), throwable);
        }
        return level.getEntity(entity.getUUID()) == null;
    }

    /** 从实体当前所在 section 摘除，防止区块保存 / 状态切换时再次被加入。 */
    private static void removeFromSection(Entity entity) {
        if (!(entity instanceof NuclearAccessor accessor)) return;
        EntityInLevelCallback callback = accessor.annihilationblade$levelCallback();
        if (callback == null) return;
        Object section = fieldValue(callback, "currentSection", "f_157611_");
        if (section instanceof EntitySection<?> generic) {
            @SuppressWarnings("unchecked")
            EntitySection<Entity> typed = (EntitySection<Entity>) (Object) generic;
            typed.remove(entity);
        }
    }

    private static void removeFromTickList(ServerLevel level, Entity entity) {
        Object tickList = fieldValue(level, "entityTickList", "f_143243_");
        if (tickList instanceof EntityTickList list) {
            list.remove(entity);
        }
    }

    private static void removeFromLookup(ServerLevel level, Entity entity) {
        Object manager = fieldValue(level, "entityManager", "f_143244_");
        if (manager == null) return;
        Object lookup = fieldValue(manager, "visibleEntityStorage", "f_157494_");
        if (lookup instanceof EntityLookup<?> generic) {
            @SuppressWarnings("unchecked")
            EntityLookup<Entity> typed = (EntityLookup<Entity>) (Object) generic;
            typed.remove(entity);
        }
    }

    private static void removeKnownUuid(ServerLevel level, Entity entity) {
        Object manager = fieldValue(level, "entityManager", "f_143244_");
        if (manager == null) return;
        Object known = fieldValue(manager, "knownUuids", "f_157491_");
        if (known instanceof Set<?> set) set.remove(entity.getUUID());
    }

    /** 销毁追踪器并广播移除包，客户端残影随之消失。 */
    private static void removeTracker(ServerLevel level, Entity entity) {
        Object chunkSource = level.getChunkSource();
        if (chunkSource instanceof ServerChunkCache cache) {
            cache.removeEntity(entity);
        }
    }

    private static void removeMiscLists(ServerLevel level, Entity entity) {
        if (entity instanceof Mob mob) {
            Object mobs = fieldValue(level, "navigatingMobs", "f_143246_");
            if (mobs instanceof Set<?> set) set.remove(mob);
        }
        Object parts = fieldValue(level, "dragonParts", "f_143247_");
        if (parts instanceof Int2ObjectMap<?> map) {
            map.remove(entity.getId());
        }
    }

    private static Object fieldValue(Object target, String official, String srg) {
        if (target == null) return null;
        Field field = cachedField(target.getClass(), official, srg);
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Field cachedField(Class<?> owner, String official, String srg) {
        ConcurrentMap<String, Field> byName = FIELD_CACHE.computeIfAbsent(owner, key -> new ConcurrentHashMap<>());
        Field cached = byName.get(official);
        if (cached != null) return cached;
        Field found = findField(owner, official);
        if (found == null) found = findField(owner, srg);
        if (found != null) byName.put(official, found);
        return found;
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> cursor = owner; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** 1.20.1 存在 {@code Entity.onRemovedFromWorld()}，1.21.1 已移除；反射调用，存在则执行。 */
    private static void removedFromWorldHook(Entity entity) {
        try {
            Method method = Entity.class.getDeclaredMethod("onRemovedFromWorld");
            method.setAccessible(true);
            method.invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }
    /** 鍚戞湰鍦板尯鍐呮墍鏈夌帺瀹跺箍鎾绉婚櫎鍖咃紝淇濊瘉瀹㈡埛绔幓闄ゅ疄浣撳奖锛堝嵆浣挎偗韪箍鏄箯澶憋級銆?*/
    static void broadcastRemoval(ServerLevel level, int entityId) {
        broadcastRemoval(level, new int[]{entityId});
    }

    static void broadcastRemoval(ServerLevel level, int[] ids) {
        if (level == null || ids == null || ids.length == 0) return;
        try {
            ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(ids);
            for (ServerPlayer player : level.players()) {
                player.connection.send(packet);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 椹遍€愬悗妫€鏌ユ畫鐣欙細鎸夌収 EntityLookup (byId) 鍜?ChunkMap 鎾勫奖鍣ㄤ袱涓眽婧愩€?*/
    static String diagnose(ServerLevel level, Entity entity) {
        if (level == null || entity == null) return "no-level-or-entity";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("lookupById=").append(level.getEntities().get(entity.getId()) != null);
        } catch (Throwable throwable) {
            sb.append("lookupById=?");
        }
        sb.append(",tracker=").append(trackerHas(level, entity));
        return sb.toString();
    }

    private static boolean trackerHas(ServerLevel level, Entity entity) {
        return entity == null ? false : trackerHas(level, entity.getId());
    }

    static boolean trackerHas(ServerLevel level, int entityId) {
        try {
            Object chunkSource = level.getChunkSource();
            Object chunkMap = fieldValue(chunkSource, "chunkMap", "f_8325_");
            if (chunkMap == null) return false;
            Object entityMap = fieldValue(chunkMap, "entityMap", "f_140150_");
            if (entityMap instanceof Int2ObjectMap<?> map) return map.containsKey(entityId);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 按实体 ID 直接从 ChunkMap 追踪器中摘除（目标 Mod 可能重建追踪器但不同步 EntityLookup）。 */
    static void removeTrackerById(ServerLevel level, int entityId) {
        try {
            Object chunkSource = level.getChunkSource();
            Object chunkMap = fieldValue(chunkSource, "chunkMap", "f_8325_");
            if (chunkMap == null) return;
            Object entityMap = fieldValue(chunkMap, "entityMap", "f_140150_");
            if (entityMap instanceof Int2ObjectMap<?> map) map.remove(entityId);
        } catch (Throwable ignored) {
        }
    }
    /** 死亡标记判断：isRemoved / removalReason 非空 / 血量<=0 / canUpdate=false。
     *  健康的新生成实体（刷怪蛋、自然生成）都不带这些标记，不会被误杀。 */
    static boolean hasDeadMarker(Entity entity) {
        if (entity == null) return false;
        try {
            if (entity.isRemoved()) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (entity.getRemovalReason() != null) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (entity instanceof LivingEntity living && living.getHealth() <= 0.0F) return true;
        } catch (Throwable ignored) {
        }
        try {
            Object canUpdate = fieldValue(entity, "canUpdate", "canUpdate");
            if (canUpdate instanceof Boolean flag && !flag) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 延迟扫描：把移除点附近同类型实体的完整状态打到日志，用于定位“生物本身残留”。 */
    static void sweep(ServerLevel level, double x, double y, double z, String typeId) {
        if (level == null) return;
        try {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity == null || entity.distanceToSqr(x, y, z) > 64.0 * 64.0) continue;
                String candidateType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                if (!candidateType.equals(typeId)) continue;
                Annihilationblade.LOGGER.warn("Nuclear sweep: {} uuid={} id={} at ({},{},{}) isRemoved={} removalReason={} health={} canUpdate={} addedToWorld={}",
                        entity, entity.getUUID(), entity.getId(), entity.getX(), entity.getY(), entity.getZ(),
                        entity.isRemoved(), removalReasonOf(entity), healthOf(entity), canUpdateOf(entity), addedToWorldOf(entity));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 机制级残留清剿：残留实体可能不在 EntityLookup（getEntity/getAll 查不到），
     *  但仍躺在 section（EntitySectionStorage）里参与碰撞/AABB 查询；
     *  AABB 查询走 section 表，能发现并摘除这类残留。 */
    static void sweepSections(ServerLevel level, double x, double y, double z, double radius, String typeId) {
        if (level == null) return;
        try {
            AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
            List<Entity> found = level.getEntitiesOfClass(Entity.class, box,
                    candidate -> candidate != null
                            && !(candidate instanceof Player)
                            && typeId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType()).toString())
                            && hasDeadMarker(candidate));
            for (Entity candidate : found) {
                Annihilationblade.LOGGER.warn("Nuclear section sweep purged: {} ({}) id={} at ({},{},{})",
                        candidate.getClass().getName(), candidate.getUUID(), candidate.getId(),
                        candidate.getX(), candidate.getY(), candidate.getZ());
                broadcastRemoval(level, candidate.getId());
                purge(candidate);
            }
        } catch (Throwable throwable) {
            Annihilationblade.LOGGER.warn("Nuclear section sweep threw at ({},{},{}): {}",
                    x, y, z, throwable.toString());
        }
    }

    private static String removalReasonOf(Entity entity) {
        try {
            Object reason = fieldValue(entity, "removalReason", "f_146795_");
            return reason == null ? "null" : reason.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String healthOf(Entity entity) {
        try {
            if (entity instanceof LivingEntity living) return String.valueOf(living.getHealth());
        } catch (Throwable ignored) {
        }
        return "?";
    }

    private static String canUpdateOf(Entity entity) {
        try {
            Object canUpdate = fieldValue(entity, "canUpdate", "canUpdate");
            return canUpdate == null ? "?" : canUpdate.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String addedToWorldOf(Entity entity) {
        try {
            Object flag = fieldValue(entity, "isAddedToWorld", "isAddedToWorld");
            return flag == null ? "?" : flag.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
