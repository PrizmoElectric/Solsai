package com.zote.contextmod.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes ItemDisplayEntity's package-private setItemStack (no public setter exists). */
@Mixin(DisplayEntity.ItemDisplayEntity.class)
public interface ItemDisplayEntityInvoker {

    @Invoker("setItemStack")
    void invokeSetItemStack(ItemStack stack);
}
