package com.zote.contextmod.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes DisplayEntity's private setters. Vanilla only ever sets these from
 * NBT/network data (e.g. /summon with a Transformation tag), so there's no
 * public API for a mod to drive them directly from server-side tick logic.
 */
@Mixin(DisplayEntity.class)
public interface DisplayEntityInvoker {

    @Invoker("setTransformation")
    void invokeSetTransformation(AffineTransformation transformation);

    @Invoker("setBillboardMode")
    void invokeSetBillboardMode(DisplayEntity.BillboardMode mode);
}
