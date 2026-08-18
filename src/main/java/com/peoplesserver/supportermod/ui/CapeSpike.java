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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
     * Describes the model the player is wearing, changing nothing.
     *
     * <p>Appending to the live model still changed the whole character, which rules out the first
     * theory (that regenerating from the skin was the problem). Before theorising again: look at
     * what the attachments actually ARE. If they turn out to be the player's clothing, adding a
     * sixth should not disturb them, and the fault is in the rebuild. If they carry weights that
     * imply selection, it is the opposite.
     */
    public static List<String> describe(Store<EntityStore> store, Ref<EntityStore> ref) {
        List<String> out = new ArrayList<>();
        ModelComponent component = store.getComponent(ref, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            out.add("no ModelComponent");
            return out;
        }
        Model model = component.getModel();
        out.add("asset=" + model.getModelAssetId() + " model=" + model.getModel()
                + " texture=" + model.getTexture());
        out.add("gradientSet=" + model.getGradientSet() + " gradientId=" + model.getGradientId()
                + " scale=" + model.getScale());
        out.add("randomAttachmentIds=" + model.getRandomAttachmentIds());
        ModelAttachment[] attachments = model.getAttachments();
        if (attachments == null) {
            out.add("attachments=null");
            return out;
        }
        out.add(attachments.length + " attachment(s):");
        for (int i = 0; i < attachments.length; i++) {
            ModelAttachment a = attachments[i];
            out.add("  [" + i + "] " + (a == null ? "null"
                    : a.getModel() + " tex=" + a.getTexture()
                            + " gset=" + a.getGradientSet() + " gid=" + a.getGradientId()
                            + " w=" + a.getWeight()));
        }
        return out;
    }

    /**
     * Rebuilds the live model with no change at all and pushes it.
     *
     * <p>The control experiment. If the character still changes after this, the fault is in
     * copying and re-sending a model — not in the cape — and no amount of tuning the attachment
     * will help.
     */
    public static Result rebuildUnchanged(Store<EntityStore> store, Ref<EntityStore> ref) {
        ModelComponent component = store.getComponent(ref, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            return new Result(false, "no ModelComponent to rebuild");
        }
        Model model = component.getModel();
        store.putComponent(ref, ModelComponent.getComponentType(),
                new ModelComponent(rebuild(model, model.getAttachments())));
        return new Result(true, "rebuilt the live model with NO changes - if your character still "
                + "changed, the fault is the rebuild, not the cape");
    }

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

        Model base;
        String source;
        if (texture == null) {
            // Reset means "throw away whatever we did and rebuild from the account's own skin",
            // so this is the one case that legitimately regenerates the model.
            PlayerSkinComponent skinComponent =
                    store.getComponent(ref, PlayerSkinComponent.getComponentType());
            if (skinComponent == null) {
                return new Result(false, "player has no PlayerSkinComponent");
            }
            PlayerSkin skin = skinComponent.getPlayerSkin();
            if (skin == null) {
                return new Result(false, "PlayerSkinComponent held a null skin");
            }
            base = cosmetics.createModel(skin);
            source = "rebuilt from skin";
        } else {
            // START FROM THE MODEL THEY ARE ACTUALLY WEARING.
            //
            // The first version built a fresh model with createModel(skin) and appended to that,
            // which put a cape on correctly and changed the rest of the character: a freshly
            // generated model is NOT the same as the live one, and everything the live model
            // carried beyond a plain skin build was silently thrown away. Appending to the
            // existing model adds a cape and touches nothing else.
            ModelComponent current = store.getComponent(ref, ModelComponent.getComponentType());
            base = current == null ? null : current.getModel();
            source = "appended to the live model";
            if (base == null) {
                return new Result(false, "player has no ModelComponent to append to");
            }
        }
        if (base == null) {
            return new Result(false, "no model available (" + source + ")");
        }

        ModelAttachment[] attachments = base.getAttachments();
        String detail;

        if (texture == null) {
            detail = "reset - " + source + ", "
                    + (attachments == null ? 0 : attachments.length) + " attachment(s)";
        } else {
            // Drop any cape we added earlier before adding this one, so running the command
            // repeatedly swaps the texture rather than stacking capes.
            ModelAttachment[] kept = attachments == null
                    ? new ModelAttachment[0]
                    : Arrays.stream(attachments)
                            .filter(a -> a == null || !CAPE_MODEL.equals(a.getModel()))
                            .toArray(ModelAttachment[]::new);

            ModelAttachment[] merged = Arrays.copyOf(kept, kept.length + 1);
            // gradientId and gradientSet stay null on purpose: the question was whether the
            // attachment renders at all. Tinting is the next experiment, and null means "no
            // gradient" rather than a wrong one.
            merged[kept.length] = new ModelAttachment(CAPE_MODEL, texture, null, null, 1.0d);
            attachments = merged;
            detail = source + ", now " + merged.length + " attachment(s) (texture " + texture + ")";
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
