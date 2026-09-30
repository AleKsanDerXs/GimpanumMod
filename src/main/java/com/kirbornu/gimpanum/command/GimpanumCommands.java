package com.kirbornu.gimpanum.command;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.core.CoreBlock;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Общий корень {@code /gimpanum}.
 *
 * <p>Ветки мода собираются здесь, а не внутри одной из них: иначе ветка Ядра
 * знала бы про конвертеры и про всё, что появится дальше.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class GimpanumCommands {

    private GimpanumCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("gimpanum")
                // Уровень 2 — тот же порог, что и на открытие настройки ПКМ.
                .requires(source -> source.hasPermission(CoreBlock.REQUIRED_PERMISSION_LEVEL));

        CoreCommand.register(root, event.getBuildContext());
        ConverterCommand.register(root, event.getBuildContext());
        PortalCommand.register(root);
        DashboardCommand.register(root);
        ConfigCommand.register(root);
        EmissionCommand.register(root);
        StatsCommand.register(root);

        event.getDispatcher().register(root);
    }
}
