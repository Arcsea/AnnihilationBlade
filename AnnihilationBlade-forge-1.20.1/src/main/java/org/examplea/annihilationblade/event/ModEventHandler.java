package org.examplea.annihilationblade.event;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.awt.Color;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.examplea.annihilationblade.Annihilationblade;
import org.examplea.annihilationblade.combat.TerminationContext;
import org.examplea.annihilationblade.combat.TerminationService;

@Mod.EventBusSubscriber(modid = Annihilationblade.MODID)
public class ModEventHandler {

    private static final ResourceLocation ANNIHILATION_SA_KEY =
            new ResourceLocation(Annihilationblade.MODID, "spatial_fracture");

    /** 通过自定义 NBT 标记或专属 SA Key 识别湮灭之刃。 */
    public static boolean isGodBlade(ItemStack stack) {
        if (stack.isEmpty()) return false;

        if (stack.hasTag() && stack.getTag().getBoolean("IsAnnihilationBlade")) return true;

        boolean[] found = {false};
        stack.getCapability(mods.flammpfeil.slashblade.item.ItemSlashBlade.BLADESTATE).ifPresent(state -> {
            ResourceLocation saKey = state.getSlashArtsKey();
            if (ANNIHILATION_SA_KEY.equals(saKey)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    public static boolean hasBladeInInventory(Player player) {
        if (isGodBlade(player.getMainHandItem())) return true;
        if (isGodBlade(player.getOffhandItem())) return true;
        for (ItemStack stack : player.getInventory().items) {
            if (isGodBlade(stack)) return true;
        }
        return false;
    }

    /**
     * 该伤害是否来自湮灭之刃持有者的攻击：主手持有神刀，或伤害直接实体是 SlashBlade
     * 召唤物且玩家背包持有神刀。供事件处理器与 Mixin 兜底触发共用。
     */
    public static boolean shouldKill(Player player, DamageSource source) {
        if (isGodBlade(player.getMainHandItem())) return true;
        Entity direct = source.getDirectEntity();
        if (direct != null && direct.getType().toString().contains("slashblade")) {
            return hasBladeInInventory(player);
        }
        return false;
    }

    private static String getKey(Player player) {
        return player.getStringUUID();
    }

    @SubscribeEvent
    @OnlyIn(Dist.CLIENT)
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!isGodBlade(stack)) return;

        List<Component> tooltip = event.getToolTip();

        Iterator<Component> it = tooltip.iterator();
        while (it.hasNext()) {
            String text = it.next().getString();
            if (text.contains("攻击伤害") || text.contains("Attack Damage") ||
                    text.contains("在主手") || text.contains("When in main hand") ||
                    text.contains("攻击速度") || text.contains("Attack Speed") ||
                    text.contains("范围") || text.contains("Range")) {
                it.remove();
            }
        }

        MutableComponent rainbowText = Component.literal(" ");
        String rawText = Component.translatable("item.annihilationblade.infinite_damage").getString();
        long time = System.currentTimeMillis();

        for (int i = 0; i < rawText.length(); i++) {
            float hue = ((time % 3000L) / 3000.0f + (i * 0.08f)) % 1.0f;
            int rgb = Color.HSBtoRGB(hue, 1.0f, 1.0f);
            rainbowText.append(Component.literal(String.valueOf(rawText.charAt(i)))
                    .withStyle(style -> style.withColor(rgb)));
        }

        tooltip.add(Component.literal(" "));
        tooltip.add(rainbowText);

        if (tooltip.size() >= 1) {
            tooltip.add(1, Component.literal(" "));
            tooltip.add(1, Component.translatable("item.annihilationblade.desc.line4").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            tooltip.add(1, Component.translatable("item.annihilationblade.desc.line3").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            tooltip.add(1, Component.translatable("item.annihilationblade.desc.line2").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            tooltip.add(1, Component.translatable("item.annihilationblade.desc.line1").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            tooltip.add(1, Component.literal(" "));
            tooltip.add(1, Component.translatable("item.annihilationblade.passive"));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(LivingHurtEvent event) {
        if (TerminationService.isInternalDamage()) return;

        Entity source = event.getSource().getEntity();
        if (source instanceof Player player
                && shouldKill(player, event.getSource())
                && TerminationService.request(event.getEntity(), player, TerminationContext.NORMAL_ATTACK)) {
            event.setCanceled(true);

            if (player.distanceTo(event.getEntity()) < 6.0f) {
                event.getEntity().level().playSound(null, event.getEntity().getX(), event.getEntity().getY(), event.getEntity().getZ(),
                        SoundEvents.TRIDENT_THUNDER, SoundSource.PLAYERS, 0.5f, 2.0f);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof net.minecraft.server.level.ServerLevel level) {
            TerminationService.tick(level);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof Player player && hasBladeInInventory(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player && hasBladeInInventory(player)) {
            event.setCanceled(true);
            player.setHealth(player.getMaxHealth());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingKnockBack(LivingKnockBackEvent event) {
        if (event.getEntity() instanceof Player player && hasBladeInInventory(player)) {
            event.setCanceled(true);
        }
    }

    private static final Set<String> playersWithFlight = new HashSet<>();
    // 持有湮灭之刃时的飞行速度倍率（默认 3 倍，可用 -Dannihilationblade.flightSpeedMult= 调整）。
    private static final float FLIGHT_SPEED_MULT = flightSpeedMultiplier();

    private static float flightSpeedMultiplier() {
        try {
            return Math.max(1.0F, Float.parseFloat(System.getProperty("annihilationblade.flightSpeedMult", "3.0")));
        } catch (RuntimeException ignored) {
            return 3.0F;
        }
    }


    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        String key = getKey(player);

        if (hasBladeInInventory(player)) {
            if (player.getHealth() < player.getMaxHealth()) player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(20.0f);
            player.removeAllEffects();

            for (List<ItemStack> compartment : java.util.Arrays.asList(
                    player.getInventory().items,
                    player.getInventory().armor,
                    player.getInventory().offhand)) {
                for (ItemStack stack : compartment) {
                    if (!isGodBlade(stack)) continue;

                    if (stack.getDamageValue() != 0) {
                        stack.setDamageValue(0);
                    }
                    if (!stack.getOrCreateTag().getBoolean("Unbreakable")) {
                        stack.getOrCreateTag().putBoolean("Unbreakable", true);
                    }
                    stack.getCapability(mods.flammpfeil.slashblade.item.ItemSlashBlade.BLADESTATE)
                            .ifPresent(state -> {
                                state.setDamage(0);
                                if (state.isBroken()) state.setBroken(false);
                            });
                }
            }

            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 220, 0, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 220, 1, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 220, 4, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 220, 0, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 220, 2, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 220, 2, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 220, 4, false, false));

            if (!player.isCreative() && !player.isSpectator()) {
                if (!player.getAbilities().mayfly) {
                    player.getAbilities().mayfly = true;
                    playersWithFlight.add(key);
                    player.onUpdateAbilities();
                }
            }
            // 提升飞行速度：仅飞行时生效，数值变化时才同步给客户端。
            float fastFlightSpeed = 0.05F * FLIGHT_SPEED_MULT;
            if (player.getAbilities().getFlyingSpeed() != fastFlightSpeed) {
                player.getAbilities().setFlyingSpeed(fastFlightSpeed);
                player.onUpdateAbilities();
            }
            if (player.getY() < -64) {
                player.teleportTo(player.getX(), 320, player.getZ());
                player.setDeltaMovement(0, 0, 0);
                if (!player.getAbilities().flying) {
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                }
            }
        } else {
            if (player.getAbilities().getFlyingSpeed() != 0.05F) {
                player.getAbilities().setFlyingSpeed(0.05F);
                player.onUpdateAbilities();
            }
            if (!player.isCreative() && !player.isSpectator() && player.getAbilities().mayfly && playersWithFlight.contains(key)) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                playersWithFlight.remove(key);
                player.onUpdateAbilities();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onItemToss(ItemTossEvent event) {
        if (isGodBlade(event.getEntity().getItem())) {
            if (!event.getPlayer().isCreative()) {
                event.setCanceled(true);
                event.getPlayer().getInventory().add(event.getEntity().getItem().copy());
            }
        }
    }
}
