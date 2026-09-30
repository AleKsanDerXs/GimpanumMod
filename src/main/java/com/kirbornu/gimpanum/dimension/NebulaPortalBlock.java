package com.kirbornu.gimpanum.dimension;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Плоскость перехода в портальной арке.
 *
 * <p>Сквозь неё можно пройти — она и переносит того, кто вошёл. Ломать и
 * ставить порталы нельзя по замыслу: прочность {@code -1} делает блок
 * неразрушимым в выживании и отсеивает его из сборки конструкций, а предмета
 * у него нет вовсе.
 */
public class NebulaPortalBlock extends Block implements EntityBlock {

    public NebulaPortalBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * Блок-сущность нужна не ради данных, а ради того, чтобы портал сам
     * записался в указатель при первой загрузке чанка — иначе выходы взять
     * неоткуда.
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NebulaPortalBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                                           BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /**
     * Вход в плоскость — перенос. Но только вход, а не стояние в ней.
     *
     * <p>Пока существо остаётся внутри, откат взводится заново каждый тик, и
     * потому никогда не истекает. Без этого выходило скверно: вышедший из
     * портала стоит рядом с аркой, задевает плоскость краем, откат тем
     * временем тикает — и через пятнадцать секунд его утаскивает обратно, хотя
     * он и шагу не сделал. Так же, слово в слово, поступает и ванильный портал
     * в {@code Entity.setAsInsidePortal}.
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (entity.isOnPortalCooldown()) {
            entity.setPortalCooldown();
            return;
        }
        if (entity.canChangeDimensions(level, serverLevel)) {
            NebulaPortal.teleport(serverLevel, entity);
        }
    }

    /** Снятая арка вычёркивается из указателя порталов. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getServer() != null) {
            PortalIndex.remove(level.getServer(), level.dimension(), pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }
}
