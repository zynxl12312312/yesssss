package com.petbot.mixin;

import com.petbot.PetFlag;
import com.petbot.PetPlayer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PlayerEntityRenderState.class)
public class PlayerEntityRenderStateMixin implements PetFlag {
    @Unique
    private PetPlayer petbot$pet;

    @Override
    public PetPlayer petbot$getPet() {
        return petbot$pet;
    }

    @Override
    public void petbot$setPet(PetPlayer pet) {
        petbot$pet = pet;
    }
}
