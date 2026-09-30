package com.kirbornu.gimpanum.converter;

import com.kirbornu.gimpanum.Gimpanum;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Чем торгуют Фонос-конвертеры, найденные в Гимпануме.
 *
 * <p>Конвертер, поставленный игроком, ничего не знает об обмене, пока его не
 * настроят командой. А вот найденный в мире должен что-то предлагать сразу —
 * и предлагать разное, иначе все находки одинаковы и искать их незачем.
 *
 * <p>Список предложений живёт в {@code config/gimpanum/converter_offers.json},
 * а не в датапаке мода: это то, что владелец сервера будет править чаще
 * всего, и лезть ради этого внутрь джарки неправильно. Файл создаётся при
 * первом запуске из образца, вшитого в джарку, и перечитывается по
 * {@code /gimpanum converter offers reload}.
 *
 * <p>Разбираем список по одному предложению, а не целиком. Половина образца
 * ссылается на предметы чужих модов, и без какого-нибудь из них разбор всего
 * списка целиком провалился бы — из-за одной строки конвертеры перестали бы
 * торговать вовсе. Непонятное предложение пропускается с записью в журнал,
 * остальные работают.
 *
 * <p>Вес — относительный: предложение с весом 20 выпадает вдвое чаще, чем с
 * весом 10. Ноль и отрицательные значения выключают предложение.
 *
 * <p>У предложения есть {@code id}, и найденный в мире конвертер запоминает
 * именно его, а не условия обмена. Поэтому правка баланса в файле действует
 * сразу на все уже стоящие конвертеры этого предложения — см.
 * {@link ConverterBlockEntity#config()}. Идентификатор менять нельзя: сменив
 * его, вы отвяжете от файла все конвертеры, которые на него ссылались.
 */
public final class ConverterOffers {

    /**
     * Одно предложение: что берут, сколько, что дают и насколько часто такое встречается.
     *
     * @param id постоянное имя предложения, по которому на него ссылается
     *           поставленный в мире конвертер. Предложение без имени работает
     *           по-старому — конвертер получит его условия снимком и с файлом
     *           больше связан не будет.
     */
    public record Offer(Optional<String> id, int weight, ItemStack input, int quota, ItemStack output,
                        int outputCount, Optional<String> label) {

        public static final Codec<Offer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("id").forGetter(Offer::id),
                // Умолчание единица, а не десять: тогда в примере видны все веса —
                // кодек не пишет поле, совпадающее с умолчанием.
                Codec.INT.optionalFieldOf("weight", 1).forGetter(Offer::weight),
                ItemStack.SINGLE_ITEM_CODEC.fieldOf("input").forGetter(Offer::input),
                Codec.INT.optionalFieldOf("quota", ConverterConfig.DEFAULT_QUOTA).forGetter(Offer::quota),
                ItemStack.SINGLE_ITEM_CODEC.fieldOf("output").forGetter(Offer::output),
                Codec.INT.optionalFieldOf("output_count", 1).forGetter(Offer::outputCount),
                Codec.STRING.optionalFieldOf("label").forGetter(Offer::label)
        ).apply(instance, Offer::new));

        public ConverterConfig toConfig() {
            return new ConverterConfig(Optional.of(input.copy()), quota,
                    Optional.of(output.copy()), outputCount, label);
        }
    }

    private static final Codec<List<Offer>> CODEC =
            Offer.CODEC.listOf().fieldOf("offers").codec();

    private static final String FILE = "converter_offers.json";

    /** Образец, из которого создаётся файл настроек при первом запуске. */
    private static final String DEFAULTS = "/data/gimpanum/converter/default_offers.json";

    private static List<Offer> offers = List.of();

    /** Предложения по именам: по ним конвертеры и находят свои условия обмена. */
    private static Map<String, Offer> byId = Map.of();

    /** Растёт при каждом чтении файла: по нему конвертеры понимают, что кэш конфига устарел. */
    private static int generation;

    private ConverterOffers() {
    }

    public static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(Gimpanum.MOD_ID).resolve(FILE);
    }

    /** Читает файл, создавая его с примерами, если файла ещё нет. */
    public static void load(MinecraftServer server) {
        Path file = path();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                writeDefaults(file);
                Gimpanum.LOGGER.info("Создан пример настроек Фонос-конвертеров: {}", file);
            }
            RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                offers = parse(ops, JsonParser.parseReader(reader));
            }
            byId = index(offers);
            generation++;
            Gimpanum.LOGGER.info("Предложений Фонос-конвертеров загружено: {}", offers.size());
        } catch (Exception failure) {
            Gimpanum.LOGGER.error("Не прочитались настройки Фонос-конвертеров {}", file, failure);
            offers = List.of();
            byId = Map.of();
            generation++;
        }
    }

    /**
     * Предложение по имени.
     *
     * <p>Пусто, если такого имени в файле нет: предложение убрали, переименовали
     * или файл не прочитался. Конвертер в этом случае доживает на снимке своих
     * условий, а не встаёт колом.
     */
    public static int generation() {
        return generation;
    }

    public static Optional<Offer> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Раскладывает предложения по именам.
     *
     * <p>Повтор имени — опечатка владельца сервера, а не замысел: два разных
     * обмена под одним именем означают, что часть конвертеров молча сменит
     * товар. Побеждает первое, остальные отбрасываются с записью в журнал.
     */
    private static Map<String, Offer> index(List<Offer> loaded) {
        Map<String, Offer> named = new LinkedHashMap<>();
        for (Offer offer : loaded) {
            offer.id().ifPresent(id -> {
                if (named.putIfAbsent(id, offer) != null) {
                    Gimpanum.LOGGER.warn("Предложение Фонос-конвертера с повторным именем «{}» пропущено", id);
                }
            });
        }
        return Map.copyOf(named);
    }

    public static int count() {
        return offers.size();
    }

    /** Случайное предложение с учётом весов. Пусто, если список пуст или все веса нулевые. */
    public static Optional<Offer> roll(RandomSource random) {
        int total = 0;
        for (Offer offer : offers) {
            if (offer.weight() > 0) {
                total += offer.weight();
            }
        }
        if (total <= 0) {
            return Optional.empty();
        }
        int pick = random.nextInt(total);
        for (Offer offer : offers) {
            if (offer.weight() <= 0) {
                continue;
            }
            pick -= offer.weight();
            if (pick < 0) {
                return Optional.of(offer);
            }
        }
        return Optional.empty();
    }

    /** Разбор по одному предложению: непонятное — мимо, остальное работает. */
    private static List<Offer> parse(RegistryOps<JsonElement> ops, JsonElement json) {
        List<Offer> parsed = new ArrayList<>();
        if (!(json instanceof JsonObject root) || !(root.get("offers") instanceof JsonArray array)) {
            Gimpanum.LOGGER.error("Настройки Фонос-конвертеров: нет списка offers");
            return List.of();
        }
        for (JsonElement element : array) {
            Offer.CODEC.parse(ops, element)
                    .resultOrPartial(error -> Gimpanum.LOGGER.warn(
                            "Предложение Фонос-конвертера пропущено ({}): {}", error, element))
                    .ifPresent(parsed::add);
        }
        return List.copyOf(parsed);
    }

    /**
     * Кладёт рядом с настройками образец из джарки — целиком, как он есть.
     *
     * <p>Копируем текстом, а не собираем кодеком из кода: в образце
     * перечислены предметы чужих модов, и собрать их в {@link ItemStack} без
     * этих модов невозможно, а образец нужен полный.
     */
    private static void writeDefaults(Path file) throws IOException {
        try (InputStream source = ConverterOffers.class.getResourceAsStream(DEFAULTS)) {
            if (source == null) {
                throw new IOException("в джарке нет образца " + DEFAULTS);
            }
            Files.copy(source, file);
        }
    }

}
