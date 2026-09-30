package com.kirbornu.gimpanum.entity;

import com.kirbornu.gimpanum.config.JsonConfig;
import com.kirbornu.gimpanum.perf.ServerLoad;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Капитан ходоков — тот, кто ведёт толпу.
 *
 * <p>Появляется в одиночку и собирает отряд сам: раз в какое-то время из
 * песка рядом с ним поднимается солдат, и чем меньше отряд, тем чаще.
 * Замечает игрока издалека и идёт к нему; толпа идёт следом, облаком вокруг
 * него. Подойдя вплотную, спускает отряд с поводка, и солдаты бросаются на ту
 * же жертву. Потерял жертву — отряд снова собирается вокруг.
 *
 * <p>Вдвое крепче и сильнее солдата, но ростом с детёныша — и в чёрной коже
 * с серым узором «Бархан». Кожа ничего не защищает, ни на нём, ни на
 * игроке: это трофей, а не доспех, и потому не изнашивается.
 */
public class DuneCaptain extends DuneWalker {

    /** Дальше этого капитан своих солдат не считает. */
    private static final double SQUAD_REACH = 64.0;

    /** Как часто пересчитывать полный отряд — вдруг кого-то убили. */
    private static final int RECOUNT = 20;

    /** Попыток найти место для нового солдата. */
    private static final int TRIES = 10;

    /** Чёрная краска — та же, что даёт ванильный чёрный краситель. */
    private static final int BLACK = 0x1D1D21;

    private static final Map<EquipmentSlot, Item> ARMOR = Map.of(
            EquipmentSlot.HEAD, Items.LEATHER_HELMET,
            EquipmentSlot.CHEST, Items.LEATHER_CHESTPLATE,
            EquipmentSlot.LEGS, Items.LEATHER_LEGGINGS,
            EquipmentSlot.FEET, Items.LEATHER_BOOTS);

    /** Отряд спущен с поводка — до тех пор, пока капитан не потеряет жертву. */
    private boolean unleashed;

    /** Сколько солдат было при последнем пересчёте. */
    private int squad;

    /** Тиков до следующего пересчёта или нового солдата. */
    private int reinforceIn;

    /** Срок уже назначен, и по его истечении поднимется солдат. */
    private boolean due;

    /** Во сколько раз быстрее обычного пополняется отряд — у капитанов Рейда Неистовых. */
    private double hurry = 1.0;

    public DuneCaptain(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
        // Предел обхода при поиске пути игра берёт как FOLLOW_RANGE * 16, а
        // чутьё у капитана дальнее — вышло бы 1536 узлов на каждый поиск. Путь
        // он строит только вблизи (см. PacedMeleeAttackGoal), и такой запас там
        // не нужен: четверть от него — это 384 узла, чего с избытком хватает
        // на два десятка блоков. Обрезаем здесь, а не в цели, потому что через
        // навигацию ходят и прочие цели — блуждание, бегство, вода.
        this.getNavigation().setMaxVisitedNodesMultiplier(0.25F);
        // Детский рост считается из isBaby, а размеры сущность запоминает при
        // создании — пересчитываем, раз ответ у нас другой, чем у зомби.
        this.refreshDimensions();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.attributes(Zombie.createAttributes(), "dune_captain")
                .add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0);
    }

    @Override
    protected String stats() {
        return "dune_captain";
    }

    /** Капитан бродит сам, когда ему некого вести: толпа всё равно идёт за ним. */
    @Override
    protected void addSquadGoals() {
    }

    @Override
    protected DuneCaptain commander() {
        return this;
    }

    /**
     * Всегда детёныш.
     *
     * <p>Через ответ, а не через ванильный флаг: флаг заодно добавляет
     * детёнышу полторы скорости, и отряд за таким капитаном бы не поспевал.
     */
    @Override
    public boolean isBaby() {
        return true;
    }

    /** Опыт как у солдата — без ванильной надбавки детёнышу в два с половиной раза. */
    @Override
    protected int getBaseExperienceReward() {
        return this.xpReward;
    }

    public boolean unleashed() {
        return unleashed;
    }

    /** Сколько солдат сейчас в отряде — по последнему пересчёту. */
    public int squadCount() {
        return squad;
    }

    /** Пополнять отряд в столько раз быстрее — до конца жизни этого капитана. */
    public void hurry(double factor) {
        hurry = Math.max(1.0, factor);
    }

    /** По бойцу ударили — отряд спускается на обидчика, если другой жертвы нет. */
    void alarm(Player attacker) {
        if (this.getTarget() == null) {
            this.setTarget(attacker);
        }
        unleashed = true;
    }

    @Override
    protected void customServerAiStep() {
        LivingEntity prey = this.getTarget();
        if (prey == null) {
            unleashed = false;
        } else if (!unleashed) {
            double release = MobStats.of("dune_captain").number("release_distance_blocks");
            unleashed = this.distanceToSqr(prey) <= release * release;
        }
        if (--reinforceIn <= 0) {
            reinforce();
        }
    }

    /**
     * Пересчитать отряд и, если пора, поднять нового солдата.
     *
     * <p>Промежуток между солдатами зависит от того, скольких не хватает:
     * у пустого отряда он самый короткий, у почти полного — самый длинный, а
     * между ними растёт ровно. Срок назначается по численности в момент
     * пересчёта, а солдат поднимается по его истечении.
     */
    private void reinforce() {
        JsonConfig.Section stats = MobStats.of("dune_captain");
        int size = stats.integer("squad_size");
        squad = members().size();
        if (squad >= size) {
            reinforceIn = RECOUNT;
            due = false;
            return;
        }
        if (!due) {
            due = true;
            int fastest = stats.integer("reinforce_fastest_ticks");
            int slowest = stats.integer("reinforce_slowest_ticks");
            reinforceIn = (int) Math.max(1, (fastest + (slowest - fastest) * squad / Math.max(1, size - 1)) / hurry);
            return;
        }
        due = false;
        // Сервер перегружен — подкрепление подождёт: солдат порождает сущности и пути.
        if (this.level().getServer() != null && ServerLoad.overloaded(this.level().getServer())) {
            reinforceIn = 40;
            return;
        }
        // Следующий срок — от новой численности, со следующего же тика.
        reinforceIn = 1;
        if (summon()) {
            squad++;
        }
    }

    /** Солдаты этого капитана, что сейчас в мире поблизости. */
    private List<DuneWalker> members() {
        return this.level().getEntitiesOfClass(DuneWalker.class, this.getBoundingBox().inflate(SQUAD_REACH),
                walker -> walker != this && walker.isAlive() && walker.serves(this.getUUID()));
    }

    /** Поднять из песка одного солдата — рядом, но не вплотную. */
    private boolean summon() {
        if (!(this.level() instanceof ServerLevel level)) {
            return false;
        }
        DuneWalker soldier = GimpanumEntities.DUNE_WALKER.get().create(level);
        if (soldier == null) {
            return false;
        }
        Vec3 at = spot(level, soldier);
        soldier.moveTo(at.x, at.y, at.z, this.random.nextFloat() * 360.0F, 0.0F);
        EventHooks.finalizeMobSpawn(soldier, level, level.getCurrentDifficultyAt(this.blockPosition()),
                MobSpawnType.REINFORCEMENT, null);
        soldier.enlist(this.getUUID());
        if (!level.addFreshEntity(soldier)) {
            return false;
        }
        BlockState ground = level.getBlockState(BlockPos.containing(at).below());
        if (!ground.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                    at.x, at.y + 0.2, at.z, 30, 0.3, 0.2, 0.3, 0.15);
            level.playSound(null, at.x, at.y, at.z, ground.getSoundType().getBreakSound(),
                    SoundSource.HOSTILE, 1.0F, 0.7F);
        }
        return true;
    }

    /**
     * Твёрдая площадка в нескольких шагах, куда встаёт солдат.
     *
     * <p>Не нашлась — прямо на месте капитана: лучше солдат в тесноте, чем
     * отряд, который не пополняется, пока капитан стоит в узком ходу.
     */
    private Vec3 spot(ServerLevel level, DuneWalker soldier) {
        RandomSource random = this.random;
        for (int attempt = 0; attempt < TRIES; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = 1.5 + random.nextDouble() * 2.5;
            BlockPos column = BlockPos.containing(this.getX() + Math.cos(angle) * distance, this.getY(),
                    this.getZ() + Math.sin(angle) * distance);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos pos = column.above(dy);
                if (level.getBlockState(pos.below()).isSolidRender(level, pos.below())
                        && level.noCollision(soldier, soldier.getType().getDimensions()
                                .makeBoundingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
                    return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                }
            }
        }
        return this.position();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (hurry > 1.0) {
            tag.putDouble("Hurry", hurry);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        hurry = tag.contains("Hurry") ? tag.getDouble("Hurry") : 1.0;
    }

    /** Погиб — отряд осиротел. */
    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.level().isClientSide) {
            members().forEach(DuneWalker::orphan);
        }
    }

    /**
     * Исчез от дальности — и отряд вместе с ним.
     *
     * <p>Только при исчезновении: при выгрузке чанка отряд засыпает вместе с
     * капитаном, при гибели — сиротеет (см. {@link #die}).
     */
    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide && reason == RemovalReason.DISCARDED) {
            members().forEach(DuneWalker::discard);
        }
        super.remove(reason);
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType spawnType, @Nullable SpawnGroupData groupData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, groupData);
        ARMOR.forEach((slot, item) -> this.setItemSlot(slot, armor(item)));
        return result;
    }

    /**
     * Шанс уронить кожу — из настройки, в момент гибели.
     *
     * <p>Не через ванильную таблицу шансов: та записывается при появлении, и
     * правка настройки дошла бы только до новых капитанов.
     */
    @Override
    protected float getEquipmentDropChance(EquipmentSlot slot) {
        return ARMOR.containsKey(slot)
                ? (float) MobStats.of("dune_captain").number("armor_drop_chance")
                : super.getEquipmentDropChance(slot);
    }

    /**
     * Кусок чёрной кожи с узором «Бархан» из железа.
     *
     * <p>Без атрибутов — брони она не даёт ни капитану, ни игроку. Неразрушима:
     * ванильное выпадение изрядно истрёпывает снятую с моба вещь, а трофею это
     * ни к чему. Подпись под именем — не курсивом, в отличие от обычной
     * подписи предмета.
     */
    private ItemStack armor(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(BLACK, false));
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        stack.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.translatable("item.gimpanum.dune_walker_armor.lore")
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY)))));
        var access = this.level().registryAccess();
        access.registryOrThrow(Registries.TRIM_MATERIAL).getHolder(TrimMaterials.IRON).ifPresent(material ->
                access.registryOrThrow(Registries.TRIM_PATTERN).getHolder(TrimPatterns.DUNE).ifPresent(pattern ->
                        stack.set(DataComponents.TRIM, new ArmorTrim(material, pattern, false))));
        return stack;
    }
}
