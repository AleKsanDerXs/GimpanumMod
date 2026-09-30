package com.kirbornu.gimpanum.dimension;

import com.kirbornu.gimpanum.sublevel.SubLevelSupport;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Отмечает портал в указателе при загрузке чанка.
 *
 * <p>Другого способа узнать, где стоят порталы, нет: структура генерируется
 * мирогеном, и сервер о ней не докладывает.
 */
public class NebulaPortalBlockEntity extends BlockEntity {

    public NebulaPortalBlockEntity(BlockPos pos, BlockState state) {
        super(GimpanumContent.NEBULA_PORTAL_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Арка на плоту корабля Sable стоит в служебном регионе карты: выходом
        // из такой записи не воспользоваться, а указатель она только засорит.
        if (level instanceof ServerLevel serverLevel && !SubLevelSupport.isOnSubLevel(level, worldPosition)) {
            PortalIndex.put(serverLevel.getServer(), level.dimension(), worldPosition);
        }
    }
}
