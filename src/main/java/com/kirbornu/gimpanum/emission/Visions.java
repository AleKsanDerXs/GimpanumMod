package com.kirbornu.gimpanum.emission;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.config.JsonConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Видения — исполинские призраки прошлого Хару.
 *
 * <p>Видение — настоящее ванильное животное, раздутое атрибутом размера до
 * десятка блоков: модель игра рисует сама, свой рендерер не нужен. Разум у него
 * отключён, оно бессмертно и неосязаемо для взаимодействий, а двигает его этот
 * класс — медленно, по воздуху и сквозь блоки, от одной точки возле того, к
 * кому оно пришло, к другой. Коснуться его — значит получать урон, пока не
 * выйдешь.
 *
 * <p>Их зовут Ностальгия и рушащееся Застывшее воспоминание. Числа — размер,
 * скорость, урон — общие, из раздела {@code nostalgia} настройки выбросов.
 *
 * <p>Видения помечены в данных сущности. Если чанк выгрузится вместе с видением
 * или сервер перезапустится, при следующей загрузке помеченное и никем не
 * ведомое видение не войдёт в мир: иначе исполинская корова так и висела бы в
 * небе навсегда.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class Visions {

    /**
     * Метка видения — тег сущности.
     *
     * <p>Раньше метка лежала в {@code getPersistentData()}, а оно лениво
     * заводит пустой NBT-тег каждой сущности, у которой его не было. Проверка
     * же идёт на вход в мир <i>любой</i> сущности сервера; тег ничего не
     * заводит. Старая метка в данных всё ещё узнаётся — но только у видов, из
     * которых мог родиться видение.
     */
    private static final String MARK = "gimpanum_vision";

    /** Кем может предстать прошлое. */
    private static final List<EntityType<? extends Mob>> ANIMALS = List.of(
            EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN, EntityType.HORSE,
            EntityType.DONKEY, EntityType.LLAMA, EntityType.RABBIT, EntityType.FOX, EntityType.WOLF,
            EntityType.CAT, EntityType.OCELOT, EntityType.PANDA, EntityType.POLAR_BEAR, EntityType.GOAT,
            EntityType.TURTLE, EntityType.FROG, EntityType.AXOLOTL, EntityType.PARROT, EntityType.CAMEL,
            EntityType.SNIFFER, EntityType.ARMADILLO, EntityType.MOOSHROOM, EntityType.BEE);

    private static final java.util.Set<EntityType<?>> ANIMAL_TYPES = java.util.Set.copyOf(ANIMALS);

    private static boolean marked(net.minecraft.world.entity.Entity entity) {
        if (entity.getTags().contains(MARK)) {
            return true;
        }
        return ANIMAL_TYPES.contains(entity.getType()) && entity.getPersistentData().getBoolean(MARK);
    }

    /** Больше этого атрибут размера не позволяет. */
    private static final double MAX_SCALE = 16.0;

    private static final class Vision {
        final ResourceKey<Level> dimension;
        final UUID owner;
        final long until;
        Vec3 goal;
        long retargetAt;

        Vision(ResourceKey<Level> dimension, UUID owner, long until, Vec3 goal) {
            this.dimension = dimension;
            this.owner = owner;
            this.until = until;
            this.goal = goal;
        }
    }

    private static final Map<UUID, Vision> ACTIVE = new HashMap<>();

    private Visions() {
    }

    /** Сколько видений живёт сейчас — для {@code /gimpanum stats}. */
    public static int active() {
        return ACTIVE.size();
    }

    /** Сколько видений сейчас пришло к этому игроку. */
    static long of(ServerPlayer owner) {
        return ACTIVE.values().stream().filter(vision -> vision.owner.equals(owner.getUUID())).count();
    }

    /**
     * Вызвать видение к игроку.
     *
     * @param distance как далеко от игрока оно возникает
     * @param life     сколько тиков живёт
     */
    public static void summon(ServerLevel level, ServerPlayer owner, double distance, int life) {
        RandomSource random = level.random;
        EntityType<? extends Mob> type = ANIMALS.get(random.nextInt(ANIMALS.size()));
        Mob mob = type.create(level);
        if (mob == null) {
            return;
        }
        double angle = random.nextDouble() * Math.PI * 2.0;
        double y = Mth.clamp(owner.getY() + 2.0 + random.nextDouble() * 6.0,
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        mob.moveTo(owner.getX() + Math.cos(angle) * distance, y, owner.getZ() + Math.sin(angle) * distance,
                random.nextFloat() * 360.0F, 0.0F);
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()),
                MobSpawnType.EVENT, null);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setInvulnerable(true);
        mob.setSilent(true);
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            double size = Math.max(type.getWidth(), type.getHeight());
            scale.setBaseValue(Math.min(MAX_SCALE, settings().number("size_blocks") / size));
            mob.refreshDimensions();
        }
        mob.addTag(MARK);

        // Сначала в список, потом в мир: при входе в мир метку проверяют, и
        // неведомое видение туда не пустили бы.
        ACTIVE.put(mob.getUUID(), new Vision(level.dimension(), owner.getUUID(), level.getGameTime() + life,
                around(owner.position(), random)));
        if (level.addFreshEntity(mob)) {
            puff(level, mob);
        } else {
            ACTIVE.remove(mob.getUUID());
        }
    }

    /** Развеять все видения разом — по окончании Ностальгии. */
    static void dispelAll(ServerLevel level) {
        for (UUID id : ACTIVE.keySet()) {
            if (level.getEntity(id) instanceof Mob mob) {
                vanish(level, mob);
            }
        }
        ACTIVE.clear();
    }

    private static JsonConfig.Section settings() {
        return EmissionConfig.of("nostalgia");
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || ACTIVE.isEmpty()) {
            return;
        }
        JsonConfig.Section config = settings();
        long now = level.getGameTime();
        boolean strike = now % Math.max(1, config.integer("damage_interval_ticks")) == 0;
        double step = config.number("speed_blocks_per_second") / 20.0;
        Iterator<Map.Entry<UUID, Vision>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Vision> entry = it.next();
            Vision vision = entry.getValue();
            // Каждое видение разбирает тик его измерения: чужой тик его не
            // найдёт и снял бы с учёта, оставив исполина висеть в небе.
            if (!vision.dimension.equals(level.dimension())) {
                continue;
            }
            Entity body = level.getEntity(entry.getKey());
            if (now >= vision.until) {
                if (body instanceof Mob mob) {
                    vanish(level, mob);
                }
                it.remove();
                continue;
            }
            if (!(body instanceof Mob mob)) {
                continue;
            }
            drift(level, mob, vision, step, now);
            if (strike) {
                touch(level, mob, config);
            }
        }
    }

    /**
     * Плыть к своей точке, изредка выбирая новую — возле хозяина, если он тут.
     *
     * <p>Разум у видения отключён, а с ним и всякое движение: игра не двигает
     * такую сущность вовсе. Поэтому ставим её сами, шаг за шагом; клиент
     * сглаживает эти шаги так же, как обычную ходьбу, и даже перебирает ногами.
     */
    private static void drift(ServerLevel level, Mob mob, Vision vision, double step, long now) {
        Vec3 position = mob.position();
        Vec3 way = vision.goal.subtract(position);
        if (way.length() < 1.0 || now >= vision.retargetAt) {
            Entity owner = level.getEntity(vision.owner);
            vision.goal = around(owner != null ? owner.position() : position, level.random);
            vision.retargetAt = now + 100 + level.random.nextInt(100);
            way = vision.goal.subtract(position);
        }
        Vec3 move = way.normalize().scale(Math.min(step, way.length()));
        double y = Mth.clamp(position.y + move.y, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        mob.setPos(position.x + move.x, y, position.z + move.z);
        float yaw = (float) (Mth.atan2(move.z, move.x) * Mth.RAD_TO_DEG) - 90.0F;
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
    }

    /** Точка поблизости от центра: в стороне и чуть выше, чтобы видение проходило рядом. */
    private static Vec3 around(Vec3 centre, RandomSource random) {
        return centre.add((random.nextDouble() - 0.5) * 20.0, random.nextDouble() * 10.0 - 2.0,
                (random.nextDouble() - 0.5) * 20.0);
    }

    /** Урон всем, кто внутри видения, — мимо брони. */
    private static void touch(ServerLevel level, Mob mob, JsonConfig.Section config) {
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, mob.getBoundingBox(),
                player -> !player.isCreative() && !player.isSpectator())) {
            player.hurt(level.damageSources().magic(), (float) config.number("damage"));
        }
    }

    private static void vanish(ServerLevel level, Mob mob) {
        puff(level, mob);
        mob.discard();
    }

    /** Облако, в котором видение возникает и тает — размером с него самого. */
    private static void puff(ServerLevel level, Mob mob) {
        double spread = mob.getBbWidth() / 2.0;
        level.sendParticles(ParticleTypes.CLOUD, mob.getX(), mob.getY() + mob.getBbHeight() / 2.0, mob.getZ(),
                80, spread, mob.getBbHeight() / 2.0, spread, 0.02);
    }

    /** Состояние статическое — в одиночной игре оно пережило бы выход в меню. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ACTIVE.clear();
    }

    /** Видение, которое никто не ведёт, в мир не входит. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && marked(event.getEntity())
                && !ACTIVE.containsKey(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    /** С видениями не поговоришь, не покормишь и верхом не сядешь. */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (marked(event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SubscribeEvent
    public static void onInteractAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (marked(event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }
}
