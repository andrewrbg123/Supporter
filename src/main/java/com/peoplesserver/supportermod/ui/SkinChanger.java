package com.peoplesserver.supportermod.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Body-tint "skins": the player's own model with its gradient swapped.
 *
 * <p><b>This is the cape spike's failure, inverted into a feature.</b> Pushing a
 * {@link ModelComponent} makes the client render exactly that model, wholesale — which is what
 * broke appending a cape (the live outfit was not in the model, so it vanished) and is exactly
 * what a skin system wants. Here the pushed model IS the live model, byte for byte, except for
 * {@code gradientSet}/{@code gradientId} — the tint the client applies to the greyscale body
 * texture. No geometry changes, no texture ships: the tints are the client's own
 * {@code Ornamented_Metal} and friends, read from GradientSets.json. Face parts are attachments
 * carrying their own gradients, so the face stays the player's own.
 *
 * <p><b>Restore is a cache, not a rebuild.</b> {@code CosmeticsModule.createModel(skin)} produces
 * a model that does NOT match the live appearance — that call is what stripped the character to
 * underwear in the spike — so "off" pushes back the model cached at first apply. The cache is
 * per-session and deliberately so: a relog rebuilds the real model from the account skin, which
 * makes relogging the always-correct fallback and this map safe to lose on restart.
 *
 * <p>All entry points must run on the world thread — every method here touches components.
 */
public final class SkinChanger {

    /** The live model each player had before their first tint, for restore. */
    private static final Map<UUID, Model> ORIGINALS = new ConcurrentHashMap<>();

    private SkinChanger() {
    }

    public record Result(boolean applied, String detail) { }

    /** Applies a tint, caching the untinted model on first use. World thread only. */
    public static Result apply(Store<EntityStore> store, Ref<EntityStore> ref, UUID uuid,
                               String gradientSet, String gradientId) {
        ModelComponent component = store.getComponent(ref, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            return new Result(false, "no model to tint");
        }
        Model live = component.getModel();
        ORIGINALS.putIfAbsent(uuid, live);
        Model tinted = rebuild(ORIGINALS.get(uuid), gradientSet, gradientId);
        store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(tinted));
        return new Result(true, gradientSet + "/" + gradientId);
    }

    /** Pushes back the cached original. World thread only. */
    public static Result restore(Store<EntityStore> store, Ref<EntityStore> ref, UUID uuid) {
        Model original = ORIGINALS.remove(uuid);
        if (original == null) {
            return new Result(false,
                    "nothing to restore in this session — relogging restores your look");
        }
        store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(original));
        return new Result(true, "restored");
    }

    /** Drops the cached original without pushing it — for disconnects. */
    public static void forget(UUID uuid) {
        ORIGINALS.remove(uuid);
    }

    /**
     * Field-for-field copy with the tint swapped. Every private field of {@code Model} has a
     * public getter — verified against the server jar before the cape spike — so the copy is
     * faithful; if a future server version adds a field without one, this silently drops it,
     * which is one reason a relog must always be able to rebuild the truth.
     */
    private static Model rebuild(Model base, String gradientSet, String gradientId) {
        return new Model(
                base.getModelAssetId(),
                base.getScale(),
                base.getRandomAttachmentIds(),
                base.getAttachments(),
                base.getBoundingBox(),
                base.getModel(),
                base.getTexture(),
                gradientSet,
                gradientId,
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
