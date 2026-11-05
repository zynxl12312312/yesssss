package com.petbot;

/** Duck interface mixed into PlayerEntityRenderState so the renderer knows which pet (if any) it is drawing. */
public interface PetFlag {
    PetPlayer petbot$getPet();

    void petbot$setPet(PetPlayer pet);
}
