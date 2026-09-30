package com.kirbornu.gimpanum.entity;

import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.Gimpanum;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Общие повадки некрофагов.
 *
 * <p>Пока здесь одно правило, но оно должно быть общим для всех: наблюдателя и
 * творца не преследуют. Ванильные цели такого игрока отбрасывают сами, а вот
 * собственные цели — погоня Призрака, обстрел Молнии — про это не знают, и
 * моб продолжал висеть на игроке, ушедшем в творческий режим. Поэтому сброс
 * сделан один раз и по тегу, а не в каждой цели по отдельности.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class NecrophageEvents {

    public static final TagKey<EntityType<?>> NECROPHAGE =
            TagKey.create(Registries.ENTITY_TYPE, Gimpanum.id("necrophage"));

    private NecrophageEvents() {
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob)
                || mob.tickCount % PerformanceConfig.necrophageInterval() != 0
                || mob.level().isClientSide
                || !mob.getType().is(NECROPHAGE)) {
            return;
        }
        LivingEntity target = mob.getTarget();
        if (target instanceof Player player && (player.isCreative() || player.isSpectator())) {
            mob.setTarget(null);
            mob.setLastHurtByMob(null);
        }
    }
}
