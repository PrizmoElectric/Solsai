package com.zote.contextmod;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * Invisible laser-blocking block placed at each shield ArmorStand's position.
 *
 * The Iron Gauntlet laser uses World.raycast(ShapeType.COLLIDER) as step 1 —
 * a block-only raycast that passes ShapeContext.absent().  By returning a full
 * cube for absent() and empty for entity contexts, this block:
 *
 *   • Stops the laser beam (raycast hits it → entity scan endpoint moves here
 *     → player behind the shield is outside the scan range → no damage)
 *   • Lets players and mobs walk through (no physical collision)
 *   • Survives the laser's destroyBlocks pass (tagged wither_immune in data pack)
 */
public class ShieldBlock extends Block {

    public ShieldBlock() {
        super(Settings.create()
            .strength(-1.0f, 3600000.0f)
            .noCollision()
            .nonOpaque()
        );
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        // ShapeContext.absent() = block raycast (laser); return full cube to stop it.
        // ShapeContext.of(entity) = entity movement; return empty so players walk through.
        return ctx == ShapeContext.absent() ? VoxelShapes.fullCube() : VoxelShapes.empty();
    }

    @Override
    public VoxelShape getSidesShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.empty();
    }

    @Override
    public VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return VoxelShapes.empty();
    }

    @Override
    public boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
        return true;
    }
}
