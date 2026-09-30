package com.kirbornu.gimpanum.command;

import com.kirbornu.gimpanum.converter.ConverterIndex;
import com.kirbornu.gimpanum.core.CoreIndex;
import com.kirbornu.gimpanum.destruction.DestructionArbiter;
import com.kirbornu.gimpanum.dimension.NebulaPortal;
import com.kirbornu.gimpanum.dimension.PortalIndex;
import com.kirbornu.gimpanum.emission.Emissions;
import com.kirbornu.gimpanum.emission.Visions;
import com.kirbornu.gimpanum.entity.GimpanumEntities;
import com.kirbornu.gimpanum.perf.ChunkLoads;
import com.kirbornu.gimpanum.structure.Later;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /gimpanum stats} — то, что мод сейчас держит в памяти и в работе.
 *
 * <p>Нужна для проверки того, что ничего не растёт: очереди подгрузки чанков,
 * указатели, ожидающие переходы и видения должны возвращаться к нулю, а не
 * копиться, и по этой команде это видно без профайлера.
 */
public final class StatsCommand {

    private StatsCommand() {
    }

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("stats").executes(context -> {
            CommandSourceStack source = context.getSource();
            MinecraftServer server = source.getServer();

            line(source, "Тик сервера, мс (среднее): %.2f", server.getAverageTickTimeNanos() / 1_000_000.0);
            line(source, "Подгрузка чанков: в работе %d, в очереди %d",
                    ChunkLoads.active(), ChunkLoads.waiting());
            line(source, "Переходы через портал в ожидании: %d", NebulaPortal.pending());
            line(source, "Указатели: Ядер %d, конвертеров %d, порталов %d",
                    CoreIndex.count(server), ConverterIndex.count(server), PortalIndex.size(server));
            line(source, "Арбитр Ядер: записей %d; отложенных действий ловушек: %d; видений: %d",
                    DestructionArbiter.pending(), Later.size(), Visions.active());
            line(source, "Идёт выброс: %s; до следующего, с: %d",
                    Emissions.current() == null ? "нет" : Emissions.current().id(),
                    Emissions.untilNext(server) / 20);

            ServerLevel level = server.getLevel(NebulaPortal.GIMPANUM);
            if (level != null) {
                Map<EntityType<?>, Integer> counts = new LinkedHashMap<>();
                counts.put(GimpanumEntities.DUNE_CAPTAIN.get(), 0);
                counts.put(GimpanumEntities.DUNE_WALKER.get(), 0);
                counts.put(GimpanumEntities.COMET_WRAITH.get(), 0);
                counts.put(GimpanumEntities.PLASMA_BOLT.get(), 0);
                counts.put(GimpanumEntities.SPACE_DEVOURER.get(), 0);
                int total = 0;
                for (Entity entity : level.getAllEntities()) {
                    total++;
                    counts.computeIfPresent(entity.getType(), (type, n) -> n + 1);
                }
                StringBuilder mobs = new StringBuilder();
                counts.forEach((type, n) -> mobs.append(EntityType.getKey(type).getPath()).append(' ')
                        .append(n).append("; "));
                line(source, "Гимпанум: сущностей %d, из них наших: %s", total, mobs.toString());
            }
            return 1;
        }));
    }

    private static void line(CommandSourceStack source, String format, Object... args) {
        String text = String.format(format, args);
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
