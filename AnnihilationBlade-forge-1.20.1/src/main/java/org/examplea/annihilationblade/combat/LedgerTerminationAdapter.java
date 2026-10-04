package org.examplea.annihilationblade.combat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.examplea.annihilationblade.Annihilationblade;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 通用账本终结适配器（机制级，不绑定任何具体 MOD）。
 * <p>
 * 一部分 MOD 把真实血量存放在私有"血量账本"字段中，并用 Mixin 在账本大于阈值时无条件
 * 取消 {@code setRemoved} / {@code isRemoved} / {@code tickDeath}：此时原版
 * {@code hurt(MAX)} 与 {@code setHealth(0)} 全部无效（原版血量槽被重写、账本从未归零），
 * 原生死亡流程永远走不完，只能靠强拆/核弹——而强拆/核弹对这类拦截式 MOD 会留下
 * "血条消失但实体静止/重复动作、退出重进才消失"的残留。
 * <p>
 * 本适配器在触发原生死亡之前先反射直写账本归零（绕过被重写的、带单次扣血上限/冷却的
 * 写入入口），拦截条件随之放行，随后 {@code die()} 走完整原生死亡流程，服务端与客户端
 * 一起干净移除。账本查找按"精确方法名 -> 精确字段名 -> 名称模糊 -> 数值启发式"四级进行；
 * 找不到账本时退化为原版强杀路径，行为与普通击杀一致。
 */
final class LedgerTerminationAdapter implements TerminationAdapter {
    /** 无冷却/无扣血上限的"账本写入"方法名（精确匹配，忽略大小写）。 */
    private static final String[] LEDGER_SETTER_NAMES = {
            "sethealts", "settitanhealth", "setgodhealth", "setbosshealth",
            "sethealthledger", "setledgerhealth", "setprivatehealth",
            "settruehealth", "setactualhealth", "setrealhealth"};
    /** 账本字段名（精确匹配，忽略大小写）。 */
    private static final String[] LEDGER_FIELD_NAMES = {
            "healts", "healths", "titanhealth", "godhealth",
            "bosshealth", "healthledger", "ledgerhealth", "privatehealth",
            "truehealth", "actualhealth", "realhealth"};
    /** 名称模糊命中：方法/字段名包含这些词（忽略大小写）且为数字类型。 */
    private static final String[] NAME_HINTS = {"heal", "hp", "ledger", "titan", "god", "boss"};
    /** 被重写/带 clamp 的原版写入入口，绝不能当作账本写入器。 */
    private static final String[] EXCLUDED_SETTERS = {"sethealth", "set_health", "heal"};
    /** 原版数值字段（坐标、行走距离、血量槽、吸收量等），不是账本。 */
    private static final String[] EXCLUDED_FIELDS = {
            "x", "y", "z", "xo", "yo", "zo", "xold", "yold", "zold",
            "prevx", "prevy", "prevz",
            "walkdist", "movedist", "health", "f_20962_",
            "absorptionamount", "f_20963_", "falldistance", "f_19865_",
            "f_19785_", "f_19786_", "f_19787_", "f_19791_", "f_19792_", "f_19793_"};
    /** 名称完全不沾边的数字字段，只有值达到该阈值才视为账本（避免误清零）。 */
    private static final double HEURISTIC_THRESHOLD = 10000.0D;

    private static final ConcurrentMap<Class<?>, List<Method>> SETTER_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, List<Field>> NAMED_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, List<Field>> HEURISTIC_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> LOGGED = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "ledger";
    }

    @Override
    public boolean matches(LivingEntity target) {
        return true; // 通用：所有终结请求都先走账本归零路径，找不到账本等价于原版强杀。
    }

    @Override
    public Outcome begin(LivingEntity target, Player attacker) {
        DamageSource source = target.level().damageSources().playerAttack(attacker);
        target.invulnerableTime = 0;
        target.setLastHurtByPlayer(attacker);
        TerminationService.runInternal(() -> {
            zeroLedger(target);                     // 1 账本归零：拦截条件解除
            target.hurt(source, Float.MAX_VALUE);   // 2 原生伤害管道（触发死亡流程）
            zeroLedger(target);                     // 3 hurt 若走重写入口会把账本改掉，再归零
            if (target.getHealth() > 0.0F) target.setHealth(0.0F);
            if (!target.isDeadOrDying()) target.die(source); // 4 原生死亡：此时拦截已放行
            zeroLedger(target);                     // 5 收尾：确保账本保持 0
        });
        return DeathAcceptanceProbe.accepted(target) ? Outcome.ACCEPTED : Outcome.REJECTED;
    }

    @Override
    public void maintain(LivingEntity target) {
        // RUNNING 期间持续压账本：Boss 若回账本/复活，立刻再归零。
        zeroLedger(target);
        if (target.getHealth() > 0.0F) target.setHealth(0.0F);
    }

    /** 反射直写账本归零；找不到账本时静默返回（等价原版路径）。 */
    static void zeroLedger(LivingEntity target) {
        if (target == null) return;
        Class<?> type = target.getClass();
        List<Method> setters = SETTER_CACHE.computeIfAbsent(type, LedgerTerminationAdapter::discoverSetters);
        List<Field> namedFields = NAMED_FIELD_CACHE.computeIfAbsent(type, LedgerTerminationAdapter::discoverNamedFields);
        List<Field> heuristicFields = HEURISTIC_FIELD_CACHE.computeIfAbsent(type, LedgerTerminationAdapter::discoverHeuristicFields);
        if (!setters.isEmpty() || !namedFields.isEmpty()) {
            if (LOGGED.add(type)) {
                Annihilationblade.LOGGER.info("Ledger termination: {} ledger member(s) found for {} (setters={}, fields={})",
                        setters.size() + namedFields.size(), type.getName(), setters.size(), namedFields.size());
            }
        }
        for (Method setter : setters) {
            try {
                setter.invoke(target, zeroValue(setter.getParameterTypes()[0]));
            } catch (Throwable ignored) {
            }
        }
        for (Field field : namedFields) {
            try {
                field.set(target, zeroValue(field.getType()));
            } catch (Throwable ignored) {
            }
        }
        for (Field field : heuristicFields) {
            try {
                if (absValue(field, target) >= HEURISTIC_THRESHOLD) {
                    field.set(target, zeroValue(field.getType()));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<Method> discoverSetters(Class<?> type) {
        List<Method> found = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())) continue;
                if (method.isSynthetic() || method.isBridge()) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || !isNumeric(params[0])) continue;
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (isExcludedSetter(name)) continue;
                boolean hit = isExactName(name, LEDGER_SETTER_NAMES)
                        || (!isMaxLike(name) && containsHint(name));
                if (!hit) continue;
                try {
                    method.setAccessible(true);
                    found.add(method);
                } catch (Throwable ignored) {
                }
            }
        }
        return found;
    }

    private static List<Field> discoverNamedFields(Class<?> type) {
        List<Field> found = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                if (!isNumeric(field.getType())) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (isExcludedField(name)) continue;
                boolean hit = isExactName(name, LEDGER_FIELD_NAMES)
                        || (!isMaxLike(name) && containsHint(name));
                if (!hit) continue;
                try {
                    field.setAccessible(true);
                    found.add(field);
                } catch (Throwable ignored) {
                }
            }
        }
        return found;
    }

    private static List<Field> discoverHeuristicFields(Class<?> type) {
        List<Field> found = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                Class<?> fieldType = field.getType();
                if (fieldType != double.class && fieldType != float.class) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (isExcludedField(name)) continue;
                if (isMaxLike(name) || containsHint(name)) continue; // 命名路径已覆盖
                try {
                    field.setAccessible(true);
                    found.add(field);
                } catch (Throwable ignored) {
                }
            }
        }
        return found;
    }

    private static double absValue(Field field, LivingEntity target) throws IllegalAccessException {
        Object value = field.get(target);
        if (value instanceof Double) return Math.abs((Double) value);
        if (value instanceof Float) return Math.abs((Float) value);
        return 0.0D;
    }

    private static Object zeroValue(Class<?> type) {
        if (type == double.class || type == Double.class) return 0.0D;
        if (type == float.class || type == Float.class) return 0.0F;
        if (type == long.class || type == Long.class) return 0L;
        if (type == int.class || type == Integer.class) return 0;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == byte.class || type == Byte.class) return (byte) 0;
        return null;
    }

    private static boolean isNumeric(Class<?> type) {
        return type == double.class || type == float.class || type == long.class
                || type == int.class || type == short.class || type == byte.class
                || type == Double.class || type == Float.class || type == Long.class
                || type == Integer.class || type == Short.class || type == Byte.class;
    }

    private static boolean isExactName(String name, String[] names) {
        for (String candidate : names) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }

    private static boolean containsHint(String name) {
        for (String hint : NAME_HINTS) {
            if (name.contains(hint)) return true;
        }
        return false;
    }

    private static boolean isMaxLike(String name) {
        return name.contains("max") || name.contains("limit");
    }

    private static boolean isExcludedSetter(String name) {
        for (String excluded : EXCLUDED_SETTERS) {
            if (excluded.equals(name)) return true;
        }
        return false;
    }

    private static boolean isExcludedField(String name) {
        for (String excluded : EXCLUDED_FIELDS) {
            if (excluded.equals(name)) return true;
        }
        return false;
    }
}
