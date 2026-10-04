package org.examplea.annihilationblade.combat;

import net.minecraft.world.entity.Entity;

/**
 * 供 {@code MixinEntity} 实现的访问器接口，使非 Mixin 代码可以安全地调用
 * 注入到 {@link net.minecraft.world.entity.Entity} 的核弹字段写入逻辑。
 *
 * <p>SpongePowered Mixin 不会改变目标类的继承关系，因此
 * {@code ((MixinEntity)(Object)entity)} 这类 class 强转会在运行期抛出
 * {@link ClassCastException}。改用接口鸭子类型：Mixin 类 {@code implements NuclearAccessor}，
 * 外部通过 {@code ((NuclearAccessor)entity).annihilationblade$forceRemove()} 调用，
 * 运行期可正常解析。</p>
 */
public interface NuclearAccessor {
    /** 直接将底层 {@code removalReason} 置为 {@link net.minecraft.world.entity.Entity.RemovalReason#DISCARDED}。 */
    void annihilationblade$forceRemove();

    /** Read the raw removalReason field directly, bypassing any isRemoved() override. */
    boolean annihilationblade$isFieldRemoved();

    /** 驱逐：调用世界实体表驱逐回调，把实体真正从世界中摘除（不经过任何可重写的实体方法）。 */
    void annihilationblade$evict();

    /** 返回实体当前挂载的世界回调（深度驱逐时读取 section 用）。 */
    net.minecraft.world.level.entity.EntityInLevelCallback annihilationblade$levelCallback();

    /** 把回调置为 NULL，防止任何后续 onRemove 重定向。 */
    void annihilationblade$nullCallback();

    /** Authoritative check: a removal marker is written without any overridable method. */
    static boolean fieldRemoved(Entity entity) {
        return entity instanceof NuclearAccessor accessor && accessor.annihilationblade$isFieldRemoved();
    }
}
