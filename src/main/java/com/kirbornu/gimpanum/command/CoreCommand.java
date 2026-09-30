package com.kirbornu.gimpanum.command;

import com.kirbornu.gimpanum.core.BoundTeam;
import com.kirbornu.gimpanum.core.CoreBlock;
import com.kirbornu.gimpanum.core.CoreBlockEntity;
import com.kirbornu.gimpanum.core.CoreConfig;
import com.kirbornu.gimpanum.core.CoreIndex;
import com.kirbornu.gimpanum.core.SpawnSettings;
import com.kirbornu.gimpanum.integration.FtbTeamsSupport;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

/**
 * Настройка Ядра командами.
 *
 * <p>Всё лежит под {@code /gimpanum core}. Ядро адресуется условным именем —
 * координаты блока на физической конструкции достигают восьмизначных значений
 * и для ручного ввода непригодны. Имя выдаётся автоматически при первой
 * загрузке ({@code core1}, {@code core2} и далее; приставка меняется командой
 * {@code /gimpanum core default_name}) и переименовывается командой
 * {@code name}. Ветка {@code at} оставлена на случай, когда имя неизвестно.
 *
 * <p>Имя может быть образцом: {@code /gimpanum core tank+ arm} задаёт все
 * Ядра, чьё имя начинается с {@code tank}, и подкоманда применится к каждому.
 * Переименование так работать не может — имена обязаны оставаться
 * уникальными.
 */
public final class CoreCommand {

    private static final SimpleCommandExceptionType ERROR_NOT_A_CORE =
            new SimpleCommandExceptionType(Component.translatable("gimpanum.command.not_a_core"));
    private static final SimpleCommandExceptionType ERROR_UNKNOWN_CORE =
            new SimpleCommandExceptionType(Component.translatable("gimpanum.command.unknown_core"));
    private static final SimpleCommandExceptionType ERROR_NO_SUCH_INDEX =
            new SimpleCommandExceptionType(Component.translatable("gimpanum.command.no_such_index"));
    private static final SimpleCommandExceptionType ERROR_NAME_TAKEN =
            new SimpleCommandExceptionType(Component.translatable("gimpanum.command.name_taken"));
    private static final SimpleCommandExceptionType ERROR_NO_MATCH =
            new SimpleCommandExceptionType(Component.translatable("gimpanum.command.no_match"));
    private static final SimpleCommandExceptionType ERROR_PATTERN_NOT_ALLOWED =
            new SimpleCommandExceptionType(Component.translatable("gimpanum.command.pattern_not_allowed"));
    private static final SimpleCommandExceptionType ERROR_NO_SUCH_TEAM =
            new SimpleCommandExceptionType(Component.translatable("gimpanum.command.no_such_team"));

    private static final SuggestionProvider<CommandSourceStack> CORE_NAMES =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    CoreIndex.names(context.getSource().getServer()), builder);
    private static final SuggestionProvider<CommandSourceStack> TEAM_NAMES =
            (context, builder) -> SharedSuggestionProvider.suggest(FtbTeamsSupport.crewNames(), builder);

    /** Ники игроков в сети — привязывают обычно присутствующих. */
    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    context.getSource().getOnlinePlayerNames(), builder);

    /**
     * Как добраться до Ядра: по имени или по координатам.
     *
     * <p>Два вопроса разделены. {@link #targets} отвечает по указателю, без
     * чанков: кто вообще имеется в виду (или ошибка адресации). {@link #loaded}
     * отдаёт только уже загруженные блоки — для подсказок, которые строятся на
     * каждое нажатие клавиши. Сами блоки выгруженных Ядер команда подгружает
     * через {@link CoreIndex#resolveAsync}: главный поток на чтение чанка не
     * ждёт.
     */
    private interface CoreResolver {
        List<UUID> targets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;

        List<CoreBlockEntity> loaded(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }

    private static final CoreResolver BY_NAME = new CoreResolver() {
        @Override
        public List<UUID> targets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            String selector = StringArgumentType.getString(context, "core");
            MinecraftServer server = context.getSource().getServer();
            if (CoreIndex.isPattern(selector)) {
                List<UUID> matched = CoreIndex.idsMatching(server, selector);
                if (matched.isEmpty()) {
                    throw ERROR_NO_MATCH.create();
                }
                return matched;
            }
            return List.of(CoreIndex.idOf(server, selector).orElseThrow(ERROR_UNKNOWN_CORE::create));
        }

        @Override
        public List<CoreBlockEntity> loaded(CommandContext<CommandSourceStack> context)
                throws CommandSyntaxException {
            String selector = StringArgumentType.getString(context, "core");
            MinecraftServer server = context.getSource().getServer();
            if (CoreIndex.isPattern(selector)) {
                return CoreIndex.findMatching(server, selector);
            }
            return List.of(CoreIndex.find(server, selector).orElseThrow(ERROR_UNKNOWN_CORE::create));
        }
    };

    private static final CoreResolver BY_POSITION = new CoreResolver() {
        @Override
        public List<UUID> targets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            return List.of(loaded(context).get(0).coreId());
        }

        @Override
        public List<CoreBlockEntity> loaded(CommandContext<CommandSourceStack> context)
                throws CommandSyntaxException {
            BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
            Level level = context.getSource().getLevel();
            if (!(level.getBlockEntity(pos) instanceof CoreBlockEntity core)) {
                throw ERROR_NOT_A_CORE.create();
            }
            return List.of(core);
        }
    };

    private CoreCommand() {
    }

    /**
     * Подсказывает уже привязанные ники — отвязывать имеет смысл только их.
     *
     * <p>Ядро на этот момент уже разобрано из аргументов, но подсказки строятся
     * и по недописанной команде, поэтому промах здесь нормален и просто даёт
     * пустой список.
     */
    private static SuggestionProvider<CommandSourceStack> boundPlayersOf(CoreResolver resolver) {
        return (context, builder) -> {
            Set<String> names = new LinkedHashSet<>();
            try {
                for (CoreBlockEntity core : resolver.loaded(context)) {
                    names.addAll(core.config().boundPlayers());
                }
            } catch (Exception ignored) {
                // Команда ещё недописана — подсказывать нечего.
            }
            return SharedSuggestionProvider.suggest(names, builder);
        };
    }

    /**
     * Навешивает ветку {@code core} на общий корень {@code /gimpanum}.
     *
     * <p>Корень строит и регистрирует {@link GimpanumCommands}: веток у мода
     * несколько, и собирать дерево внутри одной из них значило бы, что она
     * знает про все остальные.
     *
     * <p>Имя Ядра — это слово-аргумент, и Brigadier при разборе всегда
     * предпочитает ему совпавший литерал. Пока ветка имён стояла прямо в
     * корне, каждое слово рядом с ней отнимало у Ядер возможное имя: Ядро,
     * названное {@code config} или {@code portal}, переставало отзываться, и
     * догадаться о причине по сообщению об ошибке было нельзя. Собственная
     * ветка {@code core} сводит число таких слов к трём — {@code list},
     * {@code default_name} и {@code at}, — и все три относятся к самим Ядрам,
     * а не к соседним частям мода, которые будут появляться и дальше.
     */
    public static void register(LiteralArgumentBuilder<CommandSourceStack> root,
                                CommandBuildContext buildContext) {
        LiteralArgumentBuilder<CommandSourceStack> core = Commands.literal("core");

        core.then(Commands.literal("list").executes(CoreCommand::list));

        // Приставка для имён новых Ядер: общая на весь сервер, не для отдельного Ядра.
        core.then(Commands.literal("default_name")
                .then(Commands.argument("prefix", StringArgumentType.word())
                        .executes(CoreCommand::setDefaultName)));

        core.then(subcommands(
                Commands.argument("core", StringArgumentType.word()).suggests(CORE_NAMES),
                BY_NAME, buildContext));

        core.then(Commands.literal("at").then(subcommands(
                Commands.argument("pos", BlockPosArgument.blockPos()),
                BY_POSITION, buildContext)));

        root.then(core);
    }

    /**
     * Одно и то же дерево подкоманд навешивается на оба способа адресации,
     * поэтому строится функцией, а не дублируется.
     */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T subcommands(
            T parent, CoreResolver resolver, CommandBuildContext buildContext) {
        return parent
                .then(Commands.literal("show")
                        .executes(context -> show(context, resolver)))
                .then(Commands.literal("name")
                        .then(Commands.argument("new_name", StringArgumentType.word())
                                .executes(context -> rename(context, resolver))))
                // Удаление и подрыв на расстоянии. Ядро может стоять на
                // корабле в выгруженном чанке за миллионы блоков — дойти до
                // него ногами и снести киркой иногда попросту невозможно.
                .then(Commands.literal("delete")
                        .executes(context -> delete(context, resolver, false)))
                .then(Commands.literal("detonate")
                        .executes(context -> delete(context, resolver, true)))
                .then(Commands.literal("arm")
                        .executes(context -> setArmed(context, resolver, true)))
                .then(Commands.literal("disarm")
                        .executes(context -> setArmed(context, resolver, false)))
                .then(Commands.literal("invulnerable")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(context -> setInvulnerable(context, resolver))))
                .then(Commands.literal("autofragile")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(context -> setAutoFragile(context, resolver))))
                .then(Commands.literal("player")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(ONLINE_PLAYERS)
                                        .executes(context -> addPlayer(context, resolver))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(boundPlayersOf(resolver))
                                        .executes(context -> removePlayer(context, resolver))))
                        .then(Commands.literal("clear")
                                .executes(context -> clearPlayers(context, resolver))))
                // Имя экипажа берём жадно: короткие имена FTB содержат '#', а
                // отображаемые — пробелы, и обычный word() их не принимает.
                .then(Commands.literal("team")
                        .then(Commands.literal("add")
                                .then(Commands.argument("team", StringArgumentType.greedyString()).suggests(TEAM_NAMES)
                                        .executes(context -> addTeam(context, resolver))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("team", StringArgumentType.greedyString())
                                        .executes(context -> removeTeam(context, resolver))))
                        .then(Commands.literal("clear")
                                .executes(context -> clearTeams(context, resolver))))
                // Список команд один на гибель Ядра и на периодическую выдачу;
                // on_death задаёт, срабатывает ли он посмертно.
                //
                // Правка списка требует четвёртого уровня, а не второго, как
                // всё остальное: команды Ядра выполняются с правами четвёртого
                // уровня, и без этой оговорки любой, кому доверили настраивать
                // Ядра, мог бы через них выдать себе всё что угодно.
                .then(Commands.literal("command")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.literal("add")
                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                        .executes(context -> addCommand(context, resolver))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(context -> removeCommand(context, resolver))))
                        .then(Commands.literal("clear")
                                .executes(context -> clearCommands(context, resolver)))
                        .then(Commands.literal("on_death")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(context -> setDeathCommands(context, resolver)))))
                .then(Commands.literal("postfix")
                        .then(Commands.literal("set")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(context -> setPostfix(context, resolver))))
                        .then(Commands.literal("clear")
                                .executes(context -> clearPostfix(context, resolver))))
                .then(Commands.literal("seal")
                        .then(Commands.literal("enabled")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(context -> setSealEnabled(context, resolver))))
                        .then(Commands.literal("price")
                                .then(Commands.argument("price", IntegerArgumentType.integer(0))
                                        .executes(context -> setSealPrice(context, resolver)))))
                .then(Commands.literal("spawn")
                        .then(Commands.literal("enabled")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(context -> setSpawnEnabled(context, resolver))))
                        .then(Commands.literal("interval")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                        .executes(context -> setSpawnInterval(context, resolver))))
                        .then(Commands.literal("count")
                                .then(Commands.argument("count",
                                                IntegerArgumentType.integer(1, SpawnSettings.MAX_COUNT))
                                        .executes(context -> setSpawnCount(context, resolver))))
                        .then(Commands.literal("item")
                                .then(Commands.argument("item", ItemArgument.item(buildContext))
                                        .executes(context -> setSpawnItem(context, resolver))))
                        .then(Commands.literal("seal")
                                .executes(context -> clearSpawnItem(context, resolver))))
                .then(Commands.literal("explosion")
                        .then(Commands.literal("enabled")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(context -> setExplosionEnabled(context, resolver))))
                        .then(Commands.literal("power")
                                .then(Commands.argument("power", FloatArgumentType.floatArg(0.0F, 100.0F))
                                        .then(Commands.argument("fire", BoolArgumentType.bool())
                                                .executes(context -> setExplosion(context, resolver))))));
    }

    // --- Адресация -----------------------------------------------------------

    // --- Подкоманды ----------------------------------------------------------

    private static int list(CommandContext<CommandSourceStack> context) {
        List<String> names = CoreIndex.names(context.getSource().getServer());
        CommandSourceStack source = context.getSource();
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("gimpanum.command.no_cores"), false);
            return 0;
        }
        for (String name : names) {
            String where = CoreIndex.describe(source.getServer(), name).orElse("?");
            source.sendSuccess(() -> Component.literal(" • " + name + " — " + where), false);
        }
        return names.size();
    }

    /**
     * Применяет изменение ко всем выбранным Ядрам.
     *
     * <p>Все изменяющие подкоманды идут через него: иначе поддержка образцов
     * означала бы одинаковый цикл в каждой из полутора десятков.
     */
    private static int apply(CommandContext<CommandSourceStack> context, CoreResolver resolver,
                             UnaryOperator<CoreConfig> change,
                             IntFunction<Component> message) throws CommandSyntaxException {
        List<UUID> ids = resolver.targets(context);
        CommandSourceStack source = context.getSource();
        int[] done = {-1};
        CoreIndex.resolveAsync(source.getServer(), ids, cores -> {
            for (CoreBlockEntity core : cores) {
                core.setConfig(change.apply(core.config()));
            }
            int count = cores.size();
            source.sendSuccess(() -> message.apply(count), true);
            done[0] = count;
        });
        // Чанки загружены — ответ уже дан; иначе он придёт следом, когда подгрузятся.
        return done[0] >= 0 ? done[0] : ids.size();
    }

    private static int show(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        CoreIndex.resolveAsync(source.getServer(), resolver.targets(context), cores -> {
            for (CoreBlockEntity core : cores) {
                describe(source, core.config());
            }
        });
        return 1;
    }

    private static void describe(CommandSourceStack source, CoreConfig config) {
        source.sendSuccess(() -> Component.translatable("gimpanum.core.header", config.name()), false);
        source.sendSuccess(() -> Component.translatable(
                config.armed() ? "gimpanum.core.armed" : "gimpanum.core.safe"), false);

        if (config.boundPlayers().isEmpty() && config.boundTeams().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("gimpanum.core.no_players"), false);
        } else {
            for (String name : config.boundPlayers()) {
                source.sendSuccess(() -> Component.literal(" \u2022 " + name), false);
            }
            for (BoundTeam team : config.boundTeams()) {
                source.sendSuccess(() -> Component.literal(
                        " \u2691 " + team.teamName() + ": " + String.join(", ", team.members())), false);
            }
        }

        if (config.commands().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("gimpanum.core.no_commands"), false);
        } else {
            List<String> commands = config.commands();
            for (int i = 0; i < commands.size(); i++) {
                String line = " " + (i + 1) + ". " + commands.get(i);
                source.sendSuccess(() -> Component.literal(line), false);
            }
            source.sendSuccess(() -> Component.translatable("gimpanum.core.death_commands",
                    config.deathCommands()), false);
        }

        config.sealPostfix().ifPresent(postfix -> source.sendSuccess(
                () -> Component.translatable("gimpanum.core.postfix", postfix), false));
        source.sendSuccess(() -> Component.translatable("gimpanum.core.seal",
                config.sealEnabled(), config.sealPrice()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.core.spawn",
                config.spawn().enabled(), config.spawn().intervalSeconds(),
                config.spawn().item().map(ResourceLocation::toString)
                        .orElse(Component.translatable("gimpanum.core.spawn_seal").getString()),
                config.spawn().count()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.core.explosion",
                config.explosionEnabled(), config.explosionPower(), config.explosionFire()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.core.invulnerable",
                config.invulnerable()), false);
        source.sendSuccess(() -> Component.translatable("gimpanum.core.autofragile",
                config.autoDisableInvulnerable()), false);
    }

    /** Переименование работает только над одним Ядром: имена уникальны. */
    private static int rename(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        List<UUID> ids = resolver.targets(context);
        if (ids.size() != 1) {
            throw ERROR_PATTERN_NOT_ALLOWED.create();
        }
        String newName = StringArgumentType.getString(context, "new_name");
        CommandSourceStack source = context.getSource();

        if (CoreIndex.isNameTaken(source.getServer(), newName, ids.get(0))) {
            throw ERROR_NAME_TAKEN.create();
        }
        CoreIndex.resolveAsync(source.getServer(), ids, cores -> {
            if (cores.isEmpty()) {
                source.sendFailure(Component.translatable("gimpanum.command.unknown_core"));
                return;
            }
            cores.get(0).setConfig(cores.get(0).config().withName(newName));
            source.sendSuccess(() -> Component.translatable("gimpanum.command.renamed", newName), true);
        });
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> context, CoreResolver resolver,
                              boolean detonate) throws CommandSyntaxException {
        MinecraftServer server = context.getSource().getServer();
        CommandSourceStack source = context.getSource();
        List<UUID> targets = resolver.targets(context);
        int[] removed = {0};
        int[] left = {targets.size()};
        for (UUID coreId : targets) {
            CoreIndex.deleteAsync(server, coreId, detonate, ok -> {
                if (ok) {
                    removed[0]++;
                }
                if (--left[0] == 0) {
                    int count = removed[0];
                    source.sendSuccess(() -> Component.translatable(
                            detonate ? "gimpanum.command.detonated" : "gimpanum.command.deleted", count), true);
                }
            });
        }
        return targets.size();
    }

    private static int setDefaultName(CommandContext<CommandSourceStack> context) {
        String prefix = StringArgumentType.getString(context, "prefix");
        CoreIndex.setDefaultNamePrefix(context.getSource().getServer(), prefix);
        context.getSource().sendSuccess(
                () -> Component.translatable("gimpanum.command.default_name_set", prefix), true);
        return 1;
    }

    private static int setArmed(CommandContext<CommandSourceStack> context, CoreResolver resolver,
                                boolean armed) throws CommandSyntaxException {
        return apply(context, resolver, config -> config.withArmed(armed),
                count -> Component.translatable(
                        armed ? "gimpanum.command.armed" : "gimpanum.command.disarmed", count));
    }

    private static int setInvulnerable(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        boolean value = BoolArgumentType.getBool(context, "value");
        return apply(context, resolver, config -> config.withInvulnerable(value),
                count -> Component.translatable("gimpanum.command.invulnerable_set", value, count));
    }

    private static int setAutoFragile(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        boolean value = BoolArgumentType.getBool(context, "value");
        return apply(context, resolver, config -> config.withAutoDisableInvulnerable(value),
                count -> Component.translatable("gimpanum.command.autofragile_set", value, count));
    }

    private static int addPlayer(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        return apply(context, resolver, config -> {
            // Повтор просто ничего не меняет: при массовом применении часть Ядер
            // уже может быть привязана, и это не повод считать команду неудачной.
            if (config.boundPlayers().contains(name)) {
                return config;
            }
            List<String> names = new ArrayList<>(config.boundPlayers());
            names.add(name);
            return config.withBoundPlayers(names);
        }, count -> Component.translatable("gimpanum.command.player_added", name, count));
    }

    private static int removePlayer(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        return apply(context, resolver, config -> {
            List<String> names = new ArrayList<>(config.boundPlayers());
            return names.remove(name) ? config.withBoundPlayers(names) : config;
        }, count -> Component.translatable("gimpanum.command.player_removed", name, count));
    }

    private static int clearPlayers(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        return apply(context, resolver, config -> config.withBoundPlayers(List.of()),
                count -> Component.translatable("gimpanum.command.players_cleared", count));
    }

    private static int addTeam(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        String teamName = StringArgumentType.getString(context, "team");
        BoundTeam team = FtbTeamsSupport.snapshot(context.getSource().getServer(), teamName)
                .orElseThrow(ERROR_NO_SUCH_TEAM::create);

        return apply(context, resolver, config -> {
            // Повторная привязка обновляет снимок состава — это единственный
            // способ подтянуть изменившийся экипаж.
            List<BoundTeam> teams = new ArrayList<>(config.boundTeams());
            teams.removeIf(existing -> existing.teamName().equalsIgnoreCase(team.teamName()));
            teams.add(team);
            return config.withBoundTeams(teams);
        }, count -> Component.translatable("gimpanum.command.team_added",
                team.teamName(), team.members().size(), count));
    }

    private static int removeTeam(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        String teamName = StringArgumentType.getString(context, "team").trim();
        return apply(context, resolver, config -> {
            List<BoundTeam> teams = new ArrayList<>(config.boundTeams());
            return teams.removeIf(existing -> existing.teamName().equalsIgnoreCase(teamName))
                    ? config.withBoundTeams(teams) : config;
        }, count -> Component.translatable("gimpanum.command.team_removed", teamName, count));
    }

    private static int clearTeams(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        return apply(context, resolver, config -> config.withBoundTeams(List.of()),
                count -> Component.translatable("gimpanum.command.teams_cleared", count));
    }

    private static int addCommand(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        String command = StringArgumentType.getString(context, "command");
        return apply(context, resolver, config -> {
            List<String> commands = new ArrayList<>(config.commands());
            commands.add(command);
            return config.withCommands(commands);
        }, count -> Component.translatable("gimpanum.command.command_added", command, count));
    }

    private static int removeCommand(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        int index = IntegerArgumentType.getInteger(context, "index");
        return apply(context, resolver, config -> {
            List<String> commands = new ArrayList<>(config.commands());
            if (index > commands.size()) {
                return config;
            }
            commands.remove(index - 1);
            return config.withCommands(commands);
        }, count -> Component.translatable("gimpanum.command.command_removed", index, count));
    }

    private static int clearCommands(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        return apply(context, resolver, config -> config.withCommands(List.of()),
                count -> Component.translatable("gimpanum.command.commands_cleared", count));
    }

    private static int setDeathCommands(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        boolean value = BoolArgumentType.getBool(context, "value");
        return apply(context, resolver, config -> config.withDeathCommands(value),
                count -> Component.translatable("gimpanum.command.death_commands_set", value, count));
    }

    private static int setPostfix(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        String text = StringArgumentType.getString(context, "text");
        return apply(context, resolver, config -> config.withSealPostfix(Optional.of(text)),
                count -> Component.translatable("gimpanum.command.postfix_set", text, count));
    }

    private static int clearPostfix(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        return apply(context, resolver, config -> config.withSealPostfix(Optional.empty()),
                count -> Component.translatable("gimpanum.command.postfix_cleared", count));
    }

    private static int setSealEnabled(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        boolean value = BoolArgumentType.getBool(context, "value");
        return apply(context, resolver, config -> config.withSealEnabled(value),
                count -> Component.translatable("gimpanum.command.seal_enabled_set", value, count));
    }

    private static int setSealPrice(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        int price = IntegerArgumentType.getInteger(context, "price");
        return apply(context, resolver, config -> config.withSealPrice(price),
                count -> Component.translatable("gimpanum.command.price_set", price, count));
    }

    private static int setSpawnEnabled(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        boolean value = BoolArgumentType.getBool(context, "value");
        return apply(context, resolver, config -> config.withSpawn(config.spawn().withEnabled(value)),
                count -> Component.translatable("gimpanum.command.spawn_enabled_set", value, count));
    }

    private static int setSpawnInterval(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        return apply(context, resolver,
                config -> config.withSpawn(config.spawn().withIntervalSeconds(seconds)),
                count -> Component.translatable("gimpanum.command.spawn_interval_set", seconds, count));
    }

    private static int setSpawnCount(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        int count = IntegerArgumentType.getInteger(context, "count");
        return apply(context, resolver, config -> config.withSpawn(config.spawn().withCount(count)),
                result -> Component.translatable("gimpanum.command.spawn_count_set", count, result));
    }

    private static int setSpawnItem(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        Item item = ItemArgument.getItem(context, "item").getItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return apply(context, resolver,
                config -> config.withSpawn(config.spawn().withItem(Optional.of(id))),
                count -> Component.translatable("gimpanum.command.spawn_item_set",
                        item.getDescription(), count));
    }

    private static int clearSpawnItem(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        return apply(context, resolver,
                config -> config.withSpawn(config.spawn().withItem(Optional.empty())),
                count -> Component.translatable("gimpanum.command.spawn_item_seal", count));
    }

    private static int setExplosionEnabled(CommandContext<CommandSourceStack> context,
                                           CoreResolver resolver) throws CommandSyntaxException {
        boolean value = BoolArgumentType.getBool(context, "value");
        return apply(context, resolver, config -> config.withExplosionEnabled(value),
                count -> Component.translatable("gimpanum.command.explosion_enabled_set", value, count));
    }

    private static int setExplosion(CommandContext<CommandSourceStack> context, CoreResolver resolver)
            throws CommandSyntaxException {
        float power = FloatArgumentType.getFloat(context, "power");
        boolean fire = BoolArgumentType.getBool(context, "fire");
        return apply(context, resolver, config -> config.withExplosion(power, fire),
                count -> Component.translatable("gimpanum.command.explosion_set", power, fire, count));
    }
}
