package com.kirbornu.gimpanum.command;

import com.kirbornu.gimpanum.converter.ConverterOffers;
import com.kirbornu.gimpanum.emission.EmissionConfig;
import com.kirbornu.gimpanum.item.ItemConfig;
import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.structure.StructureConfig;
import com.kirbornu.gimpanum.entity.MobStats;
import com.kirbornu.gimpanum.lore.LoreBooks;
import com.kirbornu.gimpanum.network.GimpanumNetwork;
import com.kirbornu.gimpanum.recipe.ThawedOrganics;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /gimpanum config} — что мод прочитал из своей папки настроек и
 * {@code /gimpanum config reload}, чтобы прочитать заново.
 *
 * <p>Одна команда на все файлы, а не по команде на файл. Файлов у мода три —
 * предложения конвертеров, содержимое Замороженной органики, книги лора, — и
 * правят их обычно за один заход: открыл папку, поправил, перечитал. Три
 * разные ветки означали бы, что оператор должен помнить, какая из них к
 * какому файлу относится, и после правки двух файлов вызывать две команды,
 * гадая, не забыл ли третью.
 *
 * <p>Показ без {@code reload} — не украшение: чаще всего вопрос звучит «а мой
 * файл вообще подхватился?», и ответом служит число прочитанных строк рядом с
 * путём к файлу. Пропущенные строки при этом видны в журнале сервера
 * поимённо.
 */
public final class ConfigCommand {

    private ConfigCommand() {
    }

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("config")
                .then(Commands.literal("reload").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    ConverterOffers.load(source.getServer());
                    ThawedOrganics.load(source.getServer());
                    LoreBooks.load();
                    MobStats.load();
                    // Числа-атрибуты — всем, кто уже стоит в мире: иначе правка
                    // подействовала бы только на новых, и подбирать ощущение от
                    // боя пришлось бы вслепую.
                    MobStats.applyToLoaded(source.getServer());
                    EmissionConfig.load();
                    StructureConfig.load();
                    ItemConfig.load();
                    PerformanceConfig.load();
                    // Подписи конвертеров идут из предложений, а значит могли
                    // поменяться вместе с файлом — метки на карте обязаны это
                    // показать, не дожидаясь перезахода игроков.
                    GimpanumNetwork.broadcastMarkers(source.getServer());
                    // То же и со списком находок: он показывается в
                    // просмотрщике рецептов на клиенте, и правка файла обязана
                    // быть видна сразу, а не после перезахода.
                    GimpanumNetwork.broadcastThawingResults(source.getServer());
                    source.sendSuccess(
                            () -> Component.translatable("gimpanum.command.config_reloaded"), true);
                    return report(source);
                }))
                .executes(context -> report(context.getSource())));
    }

    /** @return сколько всего строк прочитано — чтобы ноль был виден и в счёте команды */
    private static int report(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("gimpanum.command.offers_count",
                ConverterOffers.count(), ConverterOffers.path().toString()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.command.thawing_count",
                ThawedOrganics.count(), ThawedOrganics.path().toString()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.command.lore_count",
                LoreBooks.count(), LoreBooks.path().toString()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.command.mobs_count",
                MobStats.count(), MobStats.path().toString()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.command.emissions_count",
                EmissionConfig.count(), EmissionConfig.path().toString()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.command.structures_count",
                StructureConfig.count(), StructureConfig.path().toString()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.command.items_count",
                ItemConfig.count(), ItemConfig.path().toString()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.command.performance_count",
                PerformanceConfig.count(), PerformanceConfig.path().toString()), false);
        return ConverterOffers.count() + ThawedOrganics.count() + LoreBooks.count();
    }
}
