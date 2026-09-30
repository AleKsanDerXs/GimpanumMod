package com.kirbornu.gimpanum.converter;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.config.JsonConfig;
import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.sublevel.SubLevelSupport;
import com.kirbornu.gimpanum.network.GimpanumNetwork;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import com.kirbornu.gimpanum.lore.LoreBooks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Копилка Фонос-конвертера и его настройка. */
public class ConverterBlockEntity extends BlockEntity {

    private static final String KEY_CONFIG = "GimpanumConverterConfig";
    private static final String KEY_PROGRESS = "GimpanumConverterProgress";
    private static final String KEY_OFFER = "GimpanumConverterOffer";
    private static final String KEY_OWED = "GimpanumConverterOwed";

    /**
     * Метка «выбрать обмен при первой загрузке».
     *
     * <p>Стоит в NBT конвертера внутри шаблона структуры. Так найденный в мире
     * конвертер получает случайное предложение, а поставленный игроком —
     * по-прежнему пустой, и разбираться, откуда взялся блок, не нужно.
     */
    private static final String KEY_ROLL = "GimpanumRollOffer";

    /**
     * Как часто конвертер осматривается в поисках брошенного.
     *
     * <p>Четверть секунды: глазу неотличимо от мгновенного, а осмотр области
     * ради каждого тика ради стоящего блока — расточительство. Воронки в ваниле
     * живут с похожим интервалом.
     */
    private static final int ABSORB_INTERVAL_TICKS = 5;

    /** С какой долей обменов конвертер отдаёт вместе с наградой чужую книгу. */
    private static final float LORE_CHANCE = 0.02F;

    /**
     * Условия обмена, записанные в самом блоке.
     *
     * <p>Для конвертера, настроенного командой, это и есть его настройка. Для
     * найденного в мире — снимок предложения на случай, если предложение из
     * файла пропадёт; обычные условия он берёт из файла, см. {@link #config()}.
     */
    private ConverterConfig stored = ConverterConfig.EMPTY;

    /**
     * Имя предложения из {@code converter_offers.json}, если конвертер к нему привязан.
     *
     * <p>Ради этой привязки всё и затевалось: пока она есть, правка баланса в
     * файле действует на уже стоящий конвертер. Настройка командой её снимает —
     * иначе указанные оператором вручную условия молча перебивались бы файлом.
     */
    private Optional<String> offerId = Optional.empty();

    private boolean rollPending;

    /** Сколько принятого набрано к текущей квоте. */
    private int progress;

    private int absorbTimer;

    /** Сколько предметов заработано, но ещё не выдано: выдача идёт порциями, см. {@code converter.max_items_per_pulse}. */
    private int owed;

    private ConverterConfig cached;

    private int cachedGeneration = -1;

    public ConverterBlockEntity(BlockPos pos, BlockState state) {
        super(GimpanumContent.PHONOS_CONVERTER_BLOCK_ENTITY.get(), pos, state);
    }

    /**
     * Действующие условия обмена.
     *
     * <p>У привязанного конвертера читаются из файла предложений при каждом
     * обращении, а не из блока. Поэтому правка файла и
     * {@code /gimpanum converter offers reload} доходят и до конвертеров в
     * выгруженных чанках: обходить мир незачем, они возьмут новое сами, как
     * только их спросят.
     */
    public ConverterConfig config() {
        // Итог кэшируется: раньше на каждый тик каждого конвертера создавался
        // новый объект. Кэш сбрасывается правкой блока и перечтением файла предложений.
        int generation = ConverterOffers.generation();
        if (cached == null || cachedGeneration != generation) {
            cached = offerId.flatMap(ConverterOffers::byId)
                    .map(ConverterOffers.Offer::toConfig)
                    .orElse(stored);
            cachedGeneration = generation;
        }
        return cached;
    }

    public int progress() {
        return progress;
    }

    public void setConfig(ConverterConfig config) {
        boolean labelChanged = !config().label().equals(config.label());
        this.stored = config;
        // Ручная настройка отвязывает от файла: с этого мгновения у конвертера
        // свои условия, и общая правка баланса его больше не касается.
        this.offerId = Optional.empty();
        this.cached = null;
        setChanged();
        // Подпись видна на карте, поэтому её правка обязана дойти до игроков.
        if (labelChanged && level != null && level.getServer() != null) {
            GimpanumNetwork.requestMarkerBroadcast();
        }
    }

    /** Обнуляет копилку, не трогая условия обмена. */
    public void resetProgress() {
        progress = 0;
        setChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            if (rollPending) {
                rollPending = false;
                ConverterOffers.roll(serverLevel.getRandom()).ifPresent(offer -> {
                    offerId = offer.id();
                    stored = offer.toConfig();
                });
                cached = null;
                setChanged();
            }
            // Конвертер на плоту корабля Sable стоит в служебном регионе карты:
            // метка на миникарте указала бы в пустоту, а указатель засорился бы.
            if (!SubLevelSupport.isOnSubLevel(level, worldPosition)) {
                ConverterIndex.put(serverLevel.getServer(), level.dimension(), worldPosition);
                GimpanumNetwork.requestMarkerBroadcast();
            }
        }
    }

    // --- Приём и выдача ------------------------------------------------------

    public void serverTick() {
        try {
            tickInternal();
        } catch (Throwable t) {
            // Тик идёт в общем цикле блок-сущностей: исключение отсюда
            // остановило бы тик всего чанка.
            Gimpanum.LOGGER.error("Тик Фонос-конвертера в {} прерван ошибкой",
                    worldPosition.toShortString(), t);
        }
    }

    private void tickInternal() {
        // Условия берутся один раз на осмотр: у привязанного конвертера каждое
        // обращение лезет в файл предложений, и незачем делать это на предмет.
        ConverterConfig config = config();
        if (!(level instanceof ServerLevel serverLevel) || !config.isOperational()) {
            return;
        }
        if (++absorbTimer < ABSORB_INTERVAL_TICKS) {
            return;
        }
        absorbTimer = 0;

        int taken = absorbNearbyItems(serverLevel, config);
        if (taken > 0) {
            progress += taken;
            setChanged();
        }
        // Выдача проверяется и без приёма: после правки баланса квота может
        // оказаться ниже уже набранного, и ждать следующего броска нечестно.
        payOut(serverLevel, config);
        release(serverLevel, config);
    }

    /**
     * Забирает лежащее на конвертере и вокруг него.
     *
     * <p>Область — сам блок, расширенный на полблока по сторонам и на блок
     * вверх: предмет, брошенный через Q, обычно ложится сверху, но может и
     * скатиться к подножию.
     *
     * <p>Стопка забирается целиком, даже если она больше остатка квоты. Излишек
     * остаётся в копилке и достанется следующему — в этом и смысл общей
     * копилки.
     */
    private int absorbNearbyItems(ServerLevel serverLevel, ConverterConfig config) {
        AABB area = new AABB(worldPosition).inflate(0.5, 0.0, 0.5)
                .expandTowards(0.0, 1.0, 0.0);
        List<ItemEntity> nearby = serverLevel.getEntitiesOfClass(ItemEntity.class, area,
                entity -> entity.isAlive() && config.matchesInput(entity.getItem()));

        int taken = 0;
        for (ItemEntity entity : nearby) {
            taken += entity.getItem().getCount();
            entity.discard();
        }
        return taken;
    }

    /**
     * Выдаёт столько раз, сколько квот набралось.
     *
     * <p>Принесённое сверх квоты не пропадает: остаток переходит к следующей.
     */
    private void payOut(ServerLevel serverLevel, ConverterConfig config) {
        int quota = config.effectiveQuota();
        int completed = progress / quota;
        if (completed <= 0) {
            return;
        }
        progress -= completed * quota;
        setChanged();

        owed += completed * config.outputCount();

        // Изредка вместе с наградой выпадает чужая книга. Конвертеры стоят
        // там, где кто-то уже побывал, и это единственный способ, которым
        // лор до сих пор доходил до живых.
        for (int i = 0; i < completed; i++) {
            if (serverLevel.random.nextFloat() < LORE_CHANCE) {
                LoreBooks.roll(serverLevel.random).ifPresent(
                        book -> Block.popResource(serverLevel, worldPosition.above(), book));
            }
        }
    }

    /**
     * Выдаёт заработанное порциями.
     *
     * <p>Раньше всё выпадало разом: при накопленном прогрессе и выходе в тысячи
     * предметов это сотни предметных сущностей в один тик. Теперь за раз
     * выдаётся не больше {@code converter.max_items_per_pulse}, а пока рядом
     * лежит больше {@code converter.pause_when_items_nearby} предметов, выдача
     * ждёт, пока их разберут. Остаток хранится в блоке и переживает перезапуск.
     */
    private void release(ServerLevel serverLevel, ConverterConfig config) {
        if (owed <= 0) {
            return;
        }
        JsonConfig.Section limits = PerformanceConfig.of("converter");
        int pause = limits.integer("pause_when_items_nearby");
        if (pause > 0 && serverLevel.getEntitiesOfClass(ItemEntity.class,
                new AABB(worldPosition).inflate(8.0)).size() > pause) {
            return;
        }
        int cap = limits.integer("max_items_per_pulse");
        int amount = cap > 0 ? Math.min(owed, cap) : owed;
        owed -= amount;
        setChanged();
        drop(serverLevel, amount, config);
    }

    /**
     * Роняет награду перед конвертером, разбивая её по стопкам.
     *
     * <p>Стопка больше {@code ItemStack.CODEC}-предела не пережила бы сохранение
     * мира, поэтому крупная выдача выпадает несколькими сущностями — так же, как
     * это делает ваниль.
     */
    private void drop(ServerLevel serverLevel, int total, ConverterConfig config) {
        ItemStack prototype = config.outputStack(1);
        if (prototype.isEmpty()) {
            return;
        }
        int remaining = total;
        int perStack = Math.max(1, prototype.getMaxStackSize());
        while (remaining > 0) {
            int size = Math.min(remaining, perStack);
            Block.popResource(serverLevel, worldPosition.above(), prototype.copyWithCount(size));
            remaining -= size;
        }
    }

    // --- Осмотр --------------------------------------------------------------

    /**
     * Курс обмена и состояние копилки — то, что игрок обязан видеть до броска.
     *
     * <p>Получатель задаётся снаружи, а не берётся игроком: тот же текст уходит
     * и в чат по правой кнопке, и в вывод команды, которую может выполнить
     * консоль, где игрока нет вовсе.
     */
    public void describe(Consumer<Component> sink) {
        ConverterConfig config = config();
        sink.accept(Component.translatable("gimpanum.converter.header", label())
                .withStyle(ChatFormatting.GOLD));

        if (!config.isOperational()) {
            sink.accept(Component.translatable("gimpanum.converter.idle")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        sink.accept(Component.translatable("gimpanum.converter.takes",
                        describeItem(config.input()), config.effectiveQuota())
                .withStyle(ChatFormatting.AQUA));
        sink.accept(Component.translatable("gimpanum.converter.gives",
                        describeItem(config.output()), config.outputCount())
                .withStyle(ChatFormatting.GREEN));
        sink.accept(Component.translatable("gimpanum.converter.progress",
                        progress, config.effectiveQuota(), config.effectiveQuota() - progress)
                .withStyle(ChatFormatting.GRAY));
    }

    public void describeTo(Player player) {
        describe(player::sendSystemMessage);
    }

    public String label() {
        return config().label().orElseGet(() -> Component
                .translatable("block.gimpanum.phonos_converter").getString());
    }

    /** Показывает предмет так, как игрок увидит его в руке — с именем и цветом. */
    private Component describeItem(Optional<ItemStack> stack) {
        return stack.map(ItemStack::getHoverName)
                .orElseGet(() -> Component.translatable("gimpanum.converter.unknown_item"));
    }

    // --- Хранение ------------------------------------------------------------

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = tag.getInt(KEY_PROGRESS);
        rollPending = tag.getBoolean(KEY_ROLL);
        owed = tag.getInt(KEY_OWED);
        cached = null;
        offerId = tag.contains(KEY_OFFER) ? Optional.of(tag.getString(KEY_OFFER)) : Optional.empty();
        if (tag.contains(KEY_CONFIG)) {
            stored = ConverterConfig.CODEC
                    .parse(NbtOps.INSTANCE, tag.get(KEY_CONFIG))
                    .resultOrPartial(error -> Gimpanum.LOGGER.error(
                            "Настройка конвертера в {} повреждена: {}",
                            getBlockPos().toShortString(), error))
                    .orElse(ConverterConfig.EMPTY);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(KEY_PROGRESS, progress);
        if (owed > 0) {
            tag.putInt(KEY_OWED, owed);
        }
        if (rollPending) {
            tag.putBoolean(KEY_ROLL, true);
        }
        offerId.ifPresent(id -> tag.putString(KEY_OFFER, id));
        ConverterConfig.CODEC.encodeStart(NbtOps.INSTANCE, stored)
                .resultOrPartial(error -> Gimpanum.LOGGER.error(
                        "Не удалось сохранить настройку конвертера в {}: {}",
                        getBlockPos().toShortString(), error))
                .ifPresent(encoded -> tag.put(KEY_CONFIG, encoded));
    }
}
