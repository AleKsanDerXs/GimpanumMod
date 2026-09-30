package com.kirbornu.gimpanum.item;

import com.kirbornu.gimpanum.integration.CorpseBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Талисман лиловой королевы — одна смерть, отменённая задним числом.
 *
 * <p>По нажатию всё, что осталось в последнем трупе владельца, оказывается у
 * него в руках, где бы этот труп ни лежал и как бы далеко ни был. Труп при
 * этом исчезает: вещи не копируются, они переезжают.
 *
 * <p>Одноразовый нарочно. Цена в сотню тотемов — это цена одного спасения, а
 * не вечной страховки; иначе первая же покупка навсегда закрывала бы тему
 * смерти для того, кто её сделал.
 *
 * <p>Впустую не тратится: если трупа нет или он пуст, Талисман остаётся в
 * руке, а игрок получает объяснение.
 */
public class PurpleQueenTalismanItem extends Item {

    public PurpleQueenTalismanItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer server)) {
            return InteractionResultHolder.success(held);
        }
        if (!CorpseBridge.available()) {
            say(server, "item.gimpanum.purple_queen_talisman.unavailable");
            return InteractionResultHolder.fail(held);
        }

        CorpseBridge.Result result = CorpseBridge.reclaim(server);
        if (result.loading()) {
            say(server, "item.gimpanum.purple_queen_talisman.loading");
            return InteractionResultHolder.fail(held);
        }
        List<ItemStack> loot = result.loot();
        if (loot.isEmpty()) {
            say(server, "item.gimpanum.purple_queen_talisman.empty");
            return InteractionResultHolder.fail(held);
        }

        for (ItemStack stack : loot) {
            // Не влезло — падает под ноги: терять возвращённое было бы издевательством.
            if (!server.getInventory().add(stack)) {
                server.drop(stack, false);
            }
        }
        level.playSound(null, server.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.2F);
        say(server, "item.gimpanum.purple_queen_talisman.done");
        held.shrink(1);
        return InteractionResultHolder.consume(held);
    }

    private static void say(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
