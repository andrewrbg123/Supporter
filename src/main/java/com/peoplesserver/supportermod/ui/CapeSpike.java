package com.peoplesserver.supportermod.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Arrays;

/**
 * A throwaway experiment to answer one question: does adding a {@link ModelAttachment} to a
 * player's model <em>layer</em> a cape onto them, or does the engine treat attachments as a
 * weighted pool it picks one from?
 *
 * <p><b>Why this exists.</b> Every vanilla cape is gated behind a Hytale edition entitlement
 * ({@code game.founder}, {@code game.deluxe}), and {@code CosmeticsModule} reads cosmetics from
 * {@code getBaseAssetPack()} only — so a plugin cannot register a cape as a cosmetic part, and
 * handing out the vanilla ones would mean giving away content people buy with the game. Going
 * through the model instead avoids both problems: the geometry is a shared client asset, exactly
 * like the vanilla particle effects the trails already use, and the texture would be ours.
 *
 * <p>What could not be settled by reading bytecode is whether attachments are additive.
 * {@code Model} carries both a {@code ModelAttachment[]} and a {@code Map randomAttachmentIds},
 * and each attachment has a {@code weight} — which reads like a pool for random NPC variation
 * rather than a list of parts all rendered at once. If it is a pool, this whole approach dies and
 * it is much better to learn that now than after building a shop around it.
 *
 * <p><b>Rebuild, do not mutate.</b> {@code Model}'s fields are private and final, so the extra
 * attachment means constructing a new {@code Model} from the old one's getters. All 25 fields have
 * public getters, so the copy is faithful — but if a future server version adds a field without a
 * getter, this silently drops it. That is one of several reasons this is a spike and not a
 * feature.
 *
 * <p>Delete this class once the question is answered.
 */
public final class CapeSpike {

    /**
     * Vanilla cape geometry. Already on every client, so nothing has to be shipped to test —
     * only the texture would become ours if this works.
     */
    public static final String CAPE_MODEL = "Cosmetics/Capes/Cape_Basic.blockymodel";

    private CapeSpike() {
    }

    /** What happened, for reporting back to whoever ran the command. */
    public record Result(boolean applied, String detail) { }

    /**
     * Rebuilds the player's model with one extra attachment.
     *
     * <p>Must be called on the world thread — it reads and writes ECS components.
     *
     * @param texture asset path for the cape texture, or null to rebuild the model with no extra
     *     attachment at all, which is the reset case
     */
    public static Result apply(Store<EntityStore> store, Ref<EntityStore> ref, String texture) {
        CosmeticsModule cosmetics = CosmeticsModule.get();
        if (cosmetics == null) {
            return new Result(false, "CosmeticsModule.get() returned null");
        }

        PlayerSkinComponent skinComponent =
                store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (skinComponent == null) {
            return new Result(false, "player has no PlayerSkinComponent");
        }
        PlayerSkin skin = skinComponent.getPlayerSkin();
        if (skin == null) {
            return new Result(false, "PlayerSkinComponent held a null skin");
        }

        // Always start from a model freshly built out of the player's own skin, so a reset is
        // simply "build it and add nothing" and repeated runs cannot stack attachments.
        Model base = cosmetics.createModel(skin);
        if (base == null) {
            return new Result(false, "createModel returned null for this skin");
        }

        ModelAttachment[] attachments = base.getAttachments();
        int existing = attachments == null ? 0 : attachments.length;
        String detail;

        if (texture == null) {
            detail = "reset to the player's own skin (" + existing + " attachment(s))";
        } else {
            ModelAttachment[] merged = attachments == null
                    ? new ModelAttachment[1]
                    : Arrays.copyOf(attachments, existing + 1);
            // gradientId and gradientSet are left null deliberately: the first question is
            // whether the attachment renders AT ALL. Tinting is a second experiment, and a null
            // here means "no gradient" rather than a wrong one.
            merged[existing] = new ModelAttachment(CAPE_MODEL, texture, null, null, 1.0d);
            attachments = merged;
            detail = "added attachment " + (existing + 1) + " of " + merged.length
                    + " (model " + CAPE_MODEL + ", texture " + texture + ")";
        }

        Model rebuilt = rebuild(base, attachments);
        // A fresh ModelComponent sets isNetworkOutdated = true in its constructor, so replacing it
        // is enough to have the change pushed - there is no setter to call.
        store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(rebuilt));
        return new Result(true, detail);
    }

    /**
     * Copies a model, swapping the attachment array.
     *
     * <p>Field-for-field against the 22-argument constructor. Verified that every private field on
     * {@code Model} has a matching public getter before writing this — the four bounding boxes
     * beyond the first are derived from the offsets, which is why the constructor takes one box
     * and four offsets rather than four boxes.
     */
    private static Model rebuild(Model base, ModelAttachment[] attachments) {
        return new Model(
                base.getModelAssetId(),
                base.getScale(),
                base.getRandomAttachmentIds(),
                attachments,
                base.getBoundingBox(),
                base.getModel(),
                base.getTexture(),
                base.getGradientSet(),
                base.getGradientId(),
                base.getEyeHeight(),
                base.getCrouchOffset(),
                base.getSittingOffset(),
                base.getSleepingOffset(),
                base.getAnimationSetMap(),
                base.getCamera(),
                base.getLight(),
                base.getParticles(),
                base.getTrails(),
                base.getPhysicsValues(),
                base.getDetailBoxes(),
                base.getPhobia(),
                base.getPhobiaModelAssetId());
    }
}
