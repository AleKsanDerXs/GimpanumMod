package com.kirbornu.gimpanum.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kirbornu.gimpanum.Gimpanum;
import net.minecraft.util.RandomSource;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map.Entry;

/**
 * Файл настроек в {@code config/gimpanum}, слитый с образцом из джарки.
 *
 * <p><b>Встроенные значения — это образец</b>, а не числа в коде. Он же ложится
 * в папку настроек при первом запуске, он же служит основой, поверх которой
 * читается файл. Так число живёт в одном месте. Образец есть и у клиента
 * выделенного сервера, который файла настроек не видит.
 *
 * <p>Файл сливается с образцом по полю, а не подменяет его. Пропущенное поле
 * берёт встроенное значение, испорченное — тоже, с записью в журнал. Так одна
 * опечатка не обнуляет весь файл: настройка не должна уметь ломать игру. Поля,
 * которых в образце нет, не читаются вовсе — это почти всегда опечатка, и о
 * ней тоже пишется в журнал.
 */
public final class JsonConfig {

    /**
     * Раздел файла.
     *
     * <p>Поле, которого нет в образце, — ошибка в коде, а не в настройке,
     * поэтому здесь оно падает сразу, а не подставляет ноль.
     */
    public record Section(String name, JsonObject json) {

        public double number(String field) {
            return get(field).getAsDouble();
        }

        public int integer(String field) {
            return get(field).getAsInt();
        }

        public boolean bool(String field) {
            return get(field).getAsBoolean();
        }

        public boolean has(String field) {
            return json.has(field);
        }

        public JsonArray list(String field) {
            return get(field).getAsJsonArray();
        }

        /** Случайное целое между полями {@code <field>_min} и {@code <field>_max} включительно. */
        public int between(String field, RandomSource random) {
            int min = integer(field + "_min");
            int max = integer(field + "_max");
            return max > min ? min + random.nextInt(max - min + 1) : min;
        }

        private JsonElement get(String field) {
            JsonElement value = json.get(field);
            if (value == null) {
                throw new IllegalArgumentException("в образце нет поля " + name + "." + field);
            }
            return value;
        }
    }

    private final String file;
    private final String resource;
    private final JsonObject builtIn;
    private JsonObject loaded;

    /**
     * @param file     имя файла в {@code config/gimpanum}
     * @param resource путь образца в джарке
     */
    public JsonConfig(String file, String resource) {
        this.file = file;
        this.resource = resource;
        this.builtIn = readBuiltIn();
        this.loaded = builtIn;
    }

    public Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(Gimpanum.MOD_ID).resolve(file);
    }

    /** Имена всех разделов — всё, кроме служебных полей с подчёркиванием. */
    public List<String> sections() {
        return loaded.keySet().stream().filter(key -> !key.startsWith("_")).toList();
    }

    /** Раздел из файла. */
    public Section section(String name) {
        return new Section(name, loaded.getAsJsonObject(name));
    }

    /** Раздел из образца — когда файла ещё нет, например при запуске игры. */
    public Section builtIn(String name) {
        return new Section(name, builtIn.getAsJsonObject(name));
    }

    /** Есть ли такой раздел в образце. */
    public boolean knows(String name) {
        return builtIn.has(name) && builtIn.get(name).isJsonObject();
    }

    public void load() {
        Path path = path();
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                try (InputStream source = open()) {
                    Files.copy(source, path);
                }
                Gimpanum.LOGGER.info("Создан пример настроек: {}", path);
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    throw new IOException("в файле не объект");
                }
                loaded = merge(file + ": ", builtIn, root.getAsJsonObject());
            }
            Gimpanum.LOGGER.info("Настройки прочитаны: {}", path);
        } catch (Exception failure) {
            Gimpanum.LOGGER.error("Не прочитались настройки {} — беру встроенные", path, failure);
            loaded = builtIn;
        }
    }

    /**
     * Образец, поверх которого лежат поля из файла.
     *
     * <p>Разделы сливаются вглубь, списки берутся из файла целиком: список —
     * это одно решение, а не набор полей, которые стоит дополнять.
     */
    private static JsonObject merge(String path, JsonObject base, JsonObject mine) {
        JsonObject out = new JsonObject();
        for (Entry<String, JsonElement> entry : base.entrySet()) {
            String key = entry.getKey();
            JsonElement fallback = entry.getValue();
            JsonElement value = mine.get(key);
            if (value == null || key.startsWith("_")) {
                out.add(key, fallback);
            } else if (fallback.isJsonObject() && value.isJsonObject()) {
                out.add(key, merge(path + key + ".", fallback.getAsJsonObject(), value.getAsJsonObject()));
            } else if (sameKind(fallback, value)) {
                out.add(key, value);
            } else {
                Gimpanum.LOGGER.warn("{}{} — не того вида, беру {}", path, key, fallback);
                out.add(key, fallback);
            }
        }
        for (String key : mine.keySet()) {
            if (!base.has(key)) {
                Gimpanum.LOGGER.warn("{}{} — такого поля нет, пропускаю", path, key);
            }
        }
        return out;
    }

    private static boolean sameKind(JsonElement a, JsonElement b) {
        if (a.isJsonArray() || b.isJsonArray()) {
            return a.isJsonArray() && b.isJsonArray();
        }
        if (!a.isJsonPrimitive() || !b.isJsonPrimitive()) {
            return false;
        }
        return a.getAsJsonPrimitive().isNumber() == b.getAsJsonPrimitive().isNumber()
                && a.getAsJsonPrimitive().isBoolean() == b.getAsJsonPrimitive().isBoolean();
    }

    private JsonObject readBuiltIn() {
        try (Reader reader = new InputStreamReader(open(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) {
            throw new IllegalStateException("образец " + resource + " не читается", failure);
        }
    }

    private InputStream open() throws IOException {
        InputStream source = JsonConfig.class.getResourceAsStream(resource);
        if (source == null) {
            throw new IOException("образец " + resource + " не найден в джарке");
        }
        return source;
    }
}
