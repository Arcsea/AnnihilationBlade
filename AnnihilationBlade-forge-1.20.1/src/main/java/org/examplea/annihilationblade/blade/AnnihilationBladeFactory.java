package org.examplea.annihilationblade.blade;

import mods.flammpfeil.slashblade.init.SBItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import org.examplea.annihilationblade.Annihilationblade;

public final class AnnihilationBladeFactory {
    private static final String BLADE_TRANSLATION_KEY = "item.annihilationblade.annihilation_blade";

    private AnnihilationBladeFactory() {
    }

    /** 创建一把以 {@code slashblade:slashblade} 为底层物品的完整湮灭之刃。 */
    public static ItemStack createGodBlade() {
        if (SBItems.slashblade == null) {
            Annihilationblade.LOGGER.warn("[AnnihilationBlade] SBItems.slashblade 尚未加载，无法创建神刀");
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(SBItems.slashblade);
        applyGodStats(stack);
        return stack;
    }

    /** 通过 SlashBlade Capability 向已有拔刀剑写入湮灭之刃属性。 */
    public static void applyGodStats(ItemStack stack) {
        stack.getOrCreateTag().putBoolean("Unbreakable", true);
        stack.setDamageValue(0);

        BladePropertyBuilder.of(stack)
                .setTranslationKey(BLADE_TRANSLATION_KEY)
                .setAnnihilationBlade(true)
                .setSlashArts("annihilationblade:spatial_fracture")
                .setModel("annihilationblade:model/blade.obj")
                .setTexture("annihilationblade:model/blade.png")
                .setDefaultBewitched(true)
                .setSealed(false)
                .setSummonedSwordColor(0xFFAA00FF)
                .setKillCount(10000)
                .setProudSoul(100000)
                .addEnchantment(Enchantments.SHARPNESS, 10)
                .addEnchantment(Enchantments.FIRE_ASPECT, 10)
                .addEnchantment(Enchantments.SMITE, 10)
                .addEnchantment(Enchantments.BANE_OF_ARTHROPODS, 10)
                .addEnchantment(Enchantments.MOB_LOOTING, 10)
                .addEnchantment(Enchantments.POWER_ARROWS, 10)
                .hideFlags(2)
                .build();
    }
}
