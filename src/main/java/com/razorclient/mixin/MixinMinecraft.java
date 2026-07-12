package com.razorclient.mixin;

import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.event.PrePlayerInteractEvent;
import com.razorclient.event.RunTickStartEvent;
import net.minecraftforge.common.MinecraftForge;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.Minecraft.class)
public abstract class MixinMinecraft {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void razorclient$onRunTickStart(CallbackInfo callbackInfo) {
        ClientRotationHelper.get().onRunTickStart();
        MinecraftForge.EVENT_BUS.post(new RunTickStartEvent());
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;getMouseOver(F)V", shift = At.Shift.BEFORE))
    private void razorclient$beforeGetMouseOver(CallbackInfo callbackInfo) {
        ClientRotationHelper.get().updateServerRotations();
    }

    @Inject(method = "runTick", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/client/settings/GameSettings;chatVisibility:Lnet/minecraft/entity/player/EntityPlayer$EnumChatVisibility;"))
    private void razorclient$beforePlayerInteract(CallbackInfo callbackInfo) {
        MinecraftForge.EVENT_BUS.post(new PrePlayerInteractEvent());
    }
}
