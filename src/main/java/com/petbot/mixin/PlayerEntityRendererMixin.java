package com.petbot.mixin;

import com.petbot.PetData;
import com.petbot.PetFlag;
import com.petbot.PetPlayer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {

    // 1.21.9+ : the renderer is generic over PlayerLikeEntity (players and mannequins)
    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void petbot$markState(PlayerLikeEntity player, PlayerEntityRenderState state, float tickDelta, CallbackInfo ci) {
        PetFlag flag = (PetFlag) state;
        if (!(player instanceof PetPlayer pet)) {
            flag.petbot$setPet(null);
            return;
        }
        flag.petbot$setPet(pet);
        PetData d = pet.getData();

        // small size: use the renderer's own scale so the feet stay exactly on the ground
        state.baseScale = PetPlayer.SCALE;

        // render the outer skin layers (hat, jacket, sleeves, pants)
        state.hatVisible = true;
        state.jacketVisible = true;
        state.leftSleeveVisible = true;
        state.rightSleeveVisible = true;
        state.leftPantsLegVisible = true;
        state.rightPantsLegVisible = true;

        // name tag: custom text, hidden if the pet has no name, and lowered to sit right above the small head
        if (d.hideName) {
            state.displayName = null;
            state.nameLabelPos = null;
        } else {
            if (state.displayName != null) {
                state.displayName = d.name;
            }
            if (state.nameLabelPos != null) {
                Vec3d pos = state.nameLabelPos;
                state.nameLabelPos = new Vec3d(pos.x, pos.y * PetPlayer.SCALE, pos.z);
            }
        }
    }

    @Inject(
            method = "getTexture(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)Lnet/minecraft/util/Identifier;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void petbot$skin(PlayerEntityRenderState state, CallbackInfoReturnable<Identifier> cir) {
        PetPlayer pet = ((PetFlag) state).petbot$getPet();
        if (pet != null) {
            cir.setReturnValue(pet.getData().texture);
        }
    }
}
