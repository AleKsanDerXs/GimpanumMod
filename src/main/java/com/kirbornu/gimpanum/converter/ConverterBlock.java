package com.kirbornu.gimpanum.converter;

import com.kirbornu.gimpanum.network.GimpanumNetwork;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Фонос-конвертер — обменник, на котором держится экономика карты.
 *
 * <p>Принимает брошенные ему предметы и, набрав квоту, выдаёт другой предмет.
 * Копилка общая: один игрок может принести половину квоты и уйти ни с чем, а
 * следующий доберёт остаток и заберёт выдачу. Цепочка из конвертеров и
 * образует экономику — первый превращает добычу в валюту, второй валюту в то,
 * ради чего игроки и ходят.
 *
 * <p>Неподвижен и неразрушим, как Ядро, и по тем же двум
 * независимым признакам: прочность {@code -1} в свойствах блока и тег
 * {@code simulated:non_movable}. Обменник обязан оставаться там, где его
 * поставил оператор, — иначе экономику можно было бы увезти на корабле.
 */
public class ConverterBlock extends Block implements EntityBlock {

    /** Настраивать конвертер может только оператор. */
    public static final int REQUIRED_PERMISSION_LEVEL = 2;

    public ConverterBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConverterBlockEntity(pos, state);
    }

    /** Тикает только на сервере: поглощение предметов — серверное дело. */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != GimpanumContent.PHONOS_CONVERTER_BLOCK_ENTITY.get()) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof ConverterBlockEntity converter) {
                converter.serverTick();
            }
        };
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }

    /**
     * Правая кнопка объясняет условия обмена.
     *
     * <p>Доступно всем без разбора прав: игрок обязан видеть курс до того, как
     * что-то бросит, иначе обменник превращается в лотерею.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ConverterBlockEntity converter)) {
            return InteractionResult.PASS;
        }
        converter.describeTo(player);
        return InteractionResult.SUCCESS;
    }

    /** Снятый конвертер вычёркивается из указателя. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getServer() != null) {
            ConverterIndex.remove(level.getServer(), level.dimension(), pos);
            GimpanumNetwork.requestMarkerBroadcast();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
