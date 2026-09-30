package com.kirbornu.gimpanum;

import com.kirbornu.gimpanum.converter.ConverterBlockEntity;
import com.kirbornu.gimpanum.converter.ConverterConfig;
import com.kirbornu.gimpanum.core.CoreBlockEntity;
import com.kirbornu.gimpanum.core.CoreIndex;
import com.kirbornu.gimpanum.dimension.NebulaPortal;
import com.kirbornu.gimpanum.dimension.PortalIndex;
import com.kirbornu.gimpanum.entity.GimpanumEntities;
import com.kirbornu.gimpanum.entity.SpaceDevourer;
import com.kirbornu.gimpanum.perf.ChunkLoads;
import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import com.kirbornu.gimpanum.structure.Later;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Игровые тесты: настоящий сервер, настоящий мир, настоящие чанки.
 *
 * <p>Запуск: {@code ./gradlew runGameTestServer}. Проверяется то, ради чего
 * затевалась переделка: подгрузка чанков не блокирует поток, переход через
 * портал завершается сам, Ядро находится после выгрузки чанка, а порционная
 * выдача конвертера не превышает потолка.
 */
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class GimpanumGameTests {

    private static final String TEMPLATE = "empty";

    private GimpanumGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GimpanumGameTests.class);
    }

    /** Заказ чанка возвращается сразу и завершается сам. */
    @GameTest(template = TEMPLATE, templateNamespace = Gimpanum.MOD_ID, timeoutTicks = 400)
    public static void chunkLoadsAreAsync(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos far = new ChunkPos(4000, 4000);
        helper.assertTrue(!ChunkLoads.loaded(level, List.of(far)), "чанк должен начинаться выгруженным");

        boolean[] done = {false};
        ChunkLoads.request(level, far, ok -> done[0] = ok);
        helper.assertTrue(!done[0], "заказ не должен завершаться синхронно");

        helper.succeedWhen(() -> {
            helper.assertTrue(done[0], "чанк не подгрузился");
        });
    }

    /** Ядро в выгруженном чанке находится через resolveAsync — чанк читается с диска без ожидания. */
    @GameTest(template = TEMPLATE, templateNamespace = Gimpanum.MOD_ID, timeoutTicks = 1200)
    public static void coreResolvedAfterUnload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos pos = new BlockPos(48000, 70, 48000);
        UUID[] id = new UUID[1];
        int[] stage = {0};

        ChunkLoads.request(level, new ChunkPos(pos), ok -> {
            level.setBlock(pos, GimpanumContent.CORE.get().defaultBlockState(), 3);
            CoreBlockEntity core = (CoreBlockEntity) level.getBlockEntity(pos);
            core.setConfig(core.config().withName("gt_core"));
            id[0] = core.coreId();
            stage[0] = 1;
        });

        helper.onEachTick(() -> {
            if (stage[0] == 1 && !level.hasChunkAt(pos)) {
                stage[0] = 2;
                CoreIndex.resolveAsync(server, List.of(id[0]), found -> stage[0] = found.size() == 1
                        && found.get(0).config().name().equals("gt_core") ? 3 : -1);
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(stage[0] == 3, "Ядро не найдено после выгрузки, стадия " + stage[0]));
    }

    /**
     * Переход через портал: известный портал лежит в выгруженном чанке далеко от игрока, поиск
     * подгружает его без блокировки, проверяет арку, находит площадку и переносит существо.
     *
     * <p>Тестовый мир — плоский верхний мир (Гимпанума в нём нет), поэтому и «отсюда», и «туда» — он
     * же. Чанки далеко, сами блоки — на плите, поставленной тестом.
     */
    @GameTest(template = TEMPLATE, templateNamespace = Gimpanum.MOD_ID, timeoutTicks = 1800)
    public static void portalArrivesAsync(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerLevel nether = level;
        BlockPos portal = new BlockPos(90000, -58, 90000);
        ChunkPos centre = new ChunkPos(portal);
        List<ChunkPos> ring = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ring.add(new ChunkPos(centre.x + dx, centre.z + dz));
            }
        }
        int[] stage = {0};
        UUID[] pigId = new UUID[1];

        ChunkLoads.request(nether, ring, ok -> {
            for (int dx = -20; dx <= 20; dx++) {
                for (int dz = -20; dz <= 20; dz++) {
                    nether.setBlock(portal.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
                    for (int dy = 0; dy <= 3; dy++) {
                        nether.setBlock(portal.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            nether.setBlock(portal, GimpanumContent.NEBULA_PORTAL.get().defaultBlockState(), 2);
            PortalIndex.put(server, net.minecraft.world.level.Level.OVERWORLD, portal);
            stage[0] = 1;
        });

        helper.onEachTick(() -> {
            // Ждём, пока чанк с порталом выгрузится: переходу придётся подгружать его самому.
            if (stage[0] == 1 && !nether.hasChunkAt(portal)) {
                stage[0] = 2;
                Entity pig = helper.spawn(EntityType.PIG, new BlockPos(2, 1, 2));
                pigId[0] = pig.getUUID();
                NebulaPortal.teleport(level, nether, pig);
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(stage[0] == 2, "портал не подготовлен, стадия " + stage[0]);
            Entity moved = nether.getEntity(pigId[0]);
            helper.assertTrue(moved != null, "существо не пришло");
            helper.assertTrue(!nether.getBlockState(moved.blockPosition()).is(GimpanumContent.NEBULA_PORTAL.get()),
                    "существо оказалось внутри арки");
            double away = moved.distanceToSqr(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5);
            helper.assertTrue(away < 30.0 * 30.0, "существо не перенеслось к порталу, до него " + Math.sqrt(away));
            helper.assertTrue(away >= 4.0, "существо вплотную к арке");
            helper.assertTrue(NebulaPortal.pending() == 0, "ожидание перехода не очистилось");
        });
    }

    /** Порционная выдача конвертера не превышает потолка, но в сумме отдаёт всё. */
    @GameTest(template = TEMPLATE, templateNamespace = Gimpanum.MOD_ID, timeoutTicks = 400)
    public static void converterPaysOutInPulses(GameTestHelper helper) {
        int cap = PerformanceConfig.of("converter").integer("max_items_per_pulse");
        int total = 1000;
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, GimpanumContent.PHONOS_CONVERTER.get());
        ConverterBlockEntity converter = (ConverterBlockEntity) helper.getBlockEntity(rel);
        converter.setConfig(new ConverterConfig(Optional.of(new ItemStack(Items.COBBLESTONE)), 1,
                Optional.of(new ItemStack(Items.DIRT)), total, Optional.empty()));
        BlockPos abs = helper.absolutePos(rel);
        helper.spawnItem(Items.COBBLESTONE, 1.5F, 2.2F, 1.5F);

        int[] first = {-1};
        AABB area = new AABB(abs).inflate(6.0);
        helper.onEachTick(() -> {
            int sum = dirt(helper.getLevel(), area);
            if (first[0] < 0 && sum > 0) {
                first[0] = sum;
            }
        });
        helper.succeedWhen(() -> {
            int sum = dirt(helper.getLevel(), area);
            helper.assertTrue(sum == total, "выдано " + sum + " из " + total);
            if (cap > 0) {
                helper.assertTrue(first[0] <= cap, "первая порция " + first[0] + " больше потолка " + cap);
            }
        });
    }

    private static int dirt(ServerLevel level, AABB area) {
        int sum = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area)) {
            if (item.getItem().is(Items.DIRT)) {
                sum += item.getItem().getCount();
            }
        }
        return sum;
    }

    /** Упавшая отложенная задача не роняет тик и не съедает соседние. */
    @GameTest(template = TEMPLATE, templateNamespace = Gimpanum.MOD_ID, timeoutTicks = 100)
    public static void laterSurvivesThrowingTask(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        boolean[] ran = {false};
        Later.run(level, 1, l -> {
            throw new IllegalStateException("намеренный сбой в тесте");
        });
        Later.run(level, 1, l -> ran[0] = true);
        helper.succeedWhen(() -> helper.assertTrue(ran[0], "вторая задача не выполнилась"));
    }

    /**
     * Указатель порталов не растёт выше portal.max_known.
     *
     * <p>Отдельная партия: тест забивает указатель, и параллельно идущему переходу это помешало бы.
     * За собой прибирает.
     */
    @GameTest(template = TEMPLATE, templateNamespace = Gimpanum.MOD_ID, timeoutTicks = 40, batch = "index")
    public static void portalIndexIsCapped(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        int cap = PerformanceConfig.of("portal").integer("max_known");
        if (cap > 0) {
            for (int i = 0; i < cap + 25; i++) {
                PortalIndex.put(server, net.minecraft.world.level.Level.END, new BlockPos(i * 16, 64, 0));
            }
            int size = PortalIndex.size(server);
            for (int i = 0; i < cap + 25; i++) {
                PortalIndex.remove(server, net.minecraft.world.level.Level.END, new BlockPos(i * 16, 64, 0));
            }
            helper.assertTrue(size <= cap, "указатель вырос до " + size);
        }
        helper.succeed();
    }

    /**
     * Поглотитель грызёт стену там, где разрешено devourer.dimensions, и не грызёт там, где нет.
     *
     * <p>Ожидание берётся из настроек: пустой список — грызёт везде, иначе только в перечисленных.
     */
    @GameTest(template = TEMPLATE, templateNamespace = Gimpanum.MOD_ID, timeoutTicks = 300)
    public static void devourerRespectsDimensionList(GameTestHelper helper) {
        var config = PerformanceConfig.of("devourer");
        boolean expectDig = config.bool("dig_enabled")
                && (config.list("dimensions").isEmpty()
                || config.list("dimensions").toString().contains("minecraft:overworld"));

        for (int y = 1; y <= 6; y++) {
            for (int z = 2; z <= 13; z++) {
                helper.setBlock(new BlockPos(8, y, z), Blocks.STONE);
            }
        }
        SpaceDevourer devourer = (SpaceDevourer) helper.spawn(GimpanumEntities.SPACE_DEVOURER.get(), new BlockPos(3, 3, 8));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos target = helper.absolutePos(new BlockPos(13, 3, 8));
        player.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);

        BlockPos probe = new BlockPos(8, 3, 8);
        helper.onEachTick(() -> devourer.setTarget(player));
        helper.runAfterDelay(200, () -> {
            boolean eaten = helper.getBlockState(probe).isAir();
            helper.assertTrue(eaten == expectDig, "стена " + (eaten ? "съедена" : "цела") + ", ожидалось "
                    + (expectDig ? "съедена" : "цела"));
            helper.succeed();
        });
    }
}
