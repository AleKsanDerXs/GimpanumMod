package com.kirbornu.gimpanum.emission;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.dimension.NebulaPortal;
import com.kirbornu.gimpanum.entity.NecrophageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Выбросы: раз в десять-сорок минут по всему Гимпануму прокатывается событие,
 * от которого среда становится злее.
 *
 * <p>Часы идут, только пока в измерении кто-то есть, и переживают перезапуск.
 * За полминуты до выброса по измерению идёт гул и приходит предупреждение, в
 * миг начала — крупное название события со своим звуком. Какое событие
 * случится, решает жребий по весам из {@code emissions.json}. Одновременно
 * идёт только один выброс.
 *
 * <p>Выброс, шедший при остановке сервера, не возобновляется: его состояние —
 * видения, отсчёты воспоминаний — живёт только в памяти.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class Emissions {

    /** Идущий выброс и его вид. */
    private record Running(EmissionKind kind, Emission emission) {
    }

    @Nullable
    private static Running running;

    /** Предупреждение о ближайшем выбросе уже прозвучало. */
    private static boolean warned;

    private Emissions() {
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        EmissionConfig.load();
    }

    /** Состояние статическое — в одиночной игре оно пережило бы выход в меню. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        running = null;
        warned = false;
        EmissionClock.get(event.getServer()).setDirty();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getLevel(NebulaPortal.GIMPANUM);
        if (level == null) {
            return;
        }
        if (running != null) {
            if (!running.emission().tick(level, targets(level))) {
                end(level);
            }
            return;
        }
        if (level.players().stream().allMatch(Player::isSpectator)) {
            return;
        }
        EmissionClock clock = EmissionClock.get(server);
        int left = clock.tickDown();
        if (!warned && left <= EmissionConfig.of("schedule").integer("warning_seconds") * 20) {
            warned = true;
            warn(level);
        }
        if (left <= 0) {
            pick(level).ifPresentOrElse(kind -> start(level, kind), () -> clock.rewind(level.random));
        }
    }

    /** Начать выброс сейчас же — по часам или по команде. Идущий прерывается. */
    public static void start(ServerLevel level, EmissionKind kind) {
        start(level, kind, targets(level));
    }

    /**
     * Начать выброс, направленный на этих игроков.
     *
     * <p>Так город Примо обрушивает Кошмар на того, кто разбудил крикунов, а
     * не на случайного.
     */
    public static void start(ServerLevel level, EmissionKind kind, List<ServerPlayer> targets) {
        if (running != null) {
            stop(level);
        }
        announce(level, kind);
        Emission emission = kind.create();
        running = new Running(kind, emission);
        emission.start(level, targets);
        EmissionClock.get(level.getServer()).rewind(level.random);
        warned = false;
    }

    /** Прервать идущий выброс. Возвращает, было ли что прерывать. */
    public static boolean stop(ServerLevel level) {
        if (running == null) {
            return false;
        }
        end(level);
        return true;
    }

    @Nullable
    public static EmissionKind current() {
        return running == null ? null : running.kind();
    }

    /** Тиков до следующего выброса. */
    public static int untilNext(MinecraftServer server) {
        return EmissionClock.get(server).left();
    }

    private static void end(ServerLevel level) {
        Running ended = running;
        running = null;
        if (ended == null) {
            return;
        }
        ended.emission().stop(level);
        if (ended.emission().lasting()) {
            level.players().forEach(player -> player.sendSystemMessage(
                    Component.translatable("gimpanum.emission.over").withStyle(ChatFormatting.GRAY)));
        }
    }

    /** Кого выброс касается: все в Гимпануме, кроме творческого режима и наблюдателей. */
    private static List<ServerPlayer> targets(ServerLevel level) {
        return level.players().stream().filter(player -> !player.isCreative() && !player.isSpectator()).toList();
    }

    /** Жребий по весам; все веса нулевые — выброса не будет. */
    private static Optional<EmissionKind> pick(ServerLevel level) {
        double total = 0.0;
        for (EmissionKind kind : EmissionKind.values()) {
            total += Math.max(0.0, kind.weight());
        }
        if (total <= 0.0) {
            return Optional.empty();
        }
        double roll = level.random.nextDouble() * total;
        for (EmissionKind kind : EmissionKind.values()) {
            roll -= Math.max(0.0, kind.weight());
            if (roll < 0.0) {
                return Optional.of(kind);
            }
        }
        return Optional.of(EmissionKind.values()[EmissionKind.values().length - 1]);
    }

    private static void warn(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(Component.translatable("gimpanum.emission.warning")
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
            sound(player, SoundEvents.AMBIENT_CAVE.value(), 0.5F);
        }
    }

    private static void announce(ServerLevel level, EmissionKind kind) {
        for (ServerPlayer player : level.players()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(kind.title().copy().withStyle(ChatFormatting.DARK_RED)));
            player.connection.send(new ClientboundSetSubtitleTextPacket(kind.hint().copy().withStyle(ChatFormatting.GRAY)));
            sound(player, kind.sound(), 1.0F);
        }
    }

    /** Звук прямо у игрока — чтобы выброс слышали все, где бы ни стояли. */
    private static void sound(ServerPlayer player, SoundEvent sound, float pitch) {
        player.playNotifySound(sound, SoundSource.HOSTILE, 1.0F, pitch);
    }

    /**
     * Во время Приступа Злости ванильное горение не бьёт.
     *
     * <p>Оно отнимает здоровье раз в секунду, а приступ бьёт реже и сам — см.
     * {@link Wrath}. Иначе урон сложился бы.
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (running != null && running.emission() instanceof Wrath
                && event.getEntity() instanceof ServerPlayer player
                && NebulaPortal.GIMPANUM.equals(player.level().dimension())
                && event.getSource().is(DamageTypes.ON_FIRE)) {
            event.setCanceled(true);
        }
    }

    /**
     * Во время Приступа Вины удар по некрофагу отзывается ударившему.
     *
     * <p>После того как урон прошёл, а не до: отзывается то, что действительно
     * досталось некрофагу, с учётом его брони. Стрела считается ударом того,
     * кто стрелял.
     */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (running != null && running.emission() instanceof Guilt
                && event.getEntity().getType().is(NecrophageEvents.NECROPHAGE)
                && event.getSource().getEntity() instanceof ServerPlayer player
                && !player.isCreative() && event.getNewDamage() > 0.0F) {
            player.hurt(player.damageSources().magic(), (float) (event.getNewDamage() * Guilt.share()));
            if (player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.SOUL, player.getX(), player.getY() + 1.0, player.getZ(),
                        6, 0.3, 0.5, 0.3, 0.02);
            }
        }
    }
}
