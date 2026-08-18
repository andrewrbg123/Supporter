package com.peoplesserver.supportermod.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
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

    /**
     * Skin name → gradientSet/gradientId. The catalogue lives here rather than in the command so
     * the login re-apply and the panel read the same source; every entry is a client-shipped
     * gradient from GradientSets.json.
     */
    public static final Map<String, String[]> SKINS = new java.util.LinkedHashMap<>();

    static {
        SKINS.put("gold", new String[] {"Ornamented_Metal", "Gold_Red"});
        SKINS.put("silver", new String[] {"Ornamented_Metal", "Silver_Blue"});
        SKINS.put("iron", new String[] {"Ornamented_Metal", "Iron_Black"});
        SKINS.put("shadow", new String[] {"Flashy_Synthetic", "Black"});
    }

    /** Registers a costume preset. Called by the plugin at startup, before any player joins. */
    public static void registerCostume(String name, String rawJson) {
        COSTUME_JSON.put(name.toLowerCase(), rawJson);
        COSTUME_PARSED.remove(name.toLowerCase());
    }

    /** Every applyable name: tints first, then costumes, for listings and validation. */
    public static java.util.List<String> allNames() {
        java.util.List<String> out = new java.util.ArrayList<>(SKINS.keySet());
        out.addAll(new java.util.TreeSet<>(COSTUME_JSON.keySet()));
        return out;
    }

    public static boolean knows(String name) {
        if (name == null) {
            return false;
        }
        String key = name.toLowerCase();
        return SKINS.containsKey(key) || COSTUME_JSON.containsKey(key);
    }

    /** Applies a catalogue skin — tint or costume — by name; unknown names are refused. */
    public static Result applyByName(Store<EntityStore> store, Ref<EntityStore> ref, UUID uuid,
                                     String name) {
        String key = name == null ? "" : name.toLowerCase();
        String[] tint = SKINS.get(key);
        if (tint != null) {
            return apply(store, ref, uuid, tint[0], tint[1]);
        }
        if (COSTUME_JSON.containsKey(key)) {
            return applyCostume(store, ref, uuid, key);
        }
        return new Result(false, "unknown skin: " + name);
    }

    /**
     * Applies a costume: swaps the fields of the player's {@code PlayerSkin} to the preset's and
     * marks the component outdated, so the client recomposes the FULL look — hair, clothing,
     * accessories — exactly as it would from the character creator. World thread only.
     *
     * <p>This is the opposite lever from the tints: a tint pushes a model (statue mode, clothing
     * packed away); a costume pushes a skin (full outfit). If a tint is active it is cleared
     * first, or the pushed model would sit over the composition and hide the costume entirely.
     */
    private static Result applyCostume(Store<EntityStore> store, Ref<EntityStore> ref, UUID uuid,
                                       String key) {
        PlayerSkin preset = COSTUME_PARSED.get(key);
        if (preset == null) {
            try {
                com.hypixel.hytale.server.core.cosmetics.CosmeticsModule cosmetics =
                        com.hypixel.hytale.server.core.cosmetics.CosmeticsModule.get();
                if (cosmetics == null) {
                    return new Result(false, "cosmetics module unavailable");
                }
                PlayerSkin parsed = cosmetics.parseSkinFromJson(COSTUME_JSON.get(key));
                cosmetics.validateSkin(parsed);
                COSTUME_PARSED.put(key, parsed);
                preset = parsed;
            } catch (Throwable t) {
                return new Result(false, "costume '" + key + "' failed to parse or validate: "
                        + t.getMessage());
            }
        }

        PlayerSkinComponent component =
                store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (component == null || component.getPlayerSkin() == null) {
            return new Result(false, "no player skin to change");
        }

        // A pushed tint model would render over the composition; take it off first.
        Model tinted = ORIGINALS.remove(uuid);
        if (tinted != null) {
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(tinted));
        }

        PlayerSkin live = component.getPlayerSkin();
        ORIGINAL_SKINS.putIfAbsent(uuid, new PlayerSkin(live));
        copyInto(preset, live);
        component.setNetworkOutdated();
        return new Result(true, "costume " + key);
    }

    /** All twenty cosmetic part fields — {@code PlayerSkin}'s fields are public and mutable. */
    private static void copyInto(PlayerSkin from, PlayerSkin to) {
        to.bodyCharacteristic = from.bodyCharacteristic;
        to.underwear = from.underwear;
        to.face = from.face;
        to.eyes = from.eyes;
        to.ears = from.ears;
        to.mouth = from.mouth;
        to.facialHair = from.facialHair;
        to.haircut = from.haircut;
        to.eyebrows = from.eyebrows;
        to.pants = from.pants;
        to.overpants = from.overpants;
        to.undertop = from.undertop;
        to.overtop = from.overtop;
        to.shoes = from.shoes;
        to.headAccessory = from.headAccessory;
        to.faceAccessory = from.faceAccessory;
        to.earAccessory = from.earAccessory;
        to.skinFeature = from.skinFeature;
        to.gloves = from.gloves;
        to.cape = from.cape;
    }

    /**
     * Costume name → raw preset JSON, registered by the plugin from
     * {@code plugins/SupporterMod/costumes/*.json} at startup and parsed lazily.
     *
     * <p>The JSON is the game's own {@code PlayerSkin} format — the same shape
     * hytalecharacter.com exports — so an admin composes a costume there, drops the file in the
     * folder, and it is a supporter skin after a restart with no rebuild. Parsing is lazy
     * because {@code CosmeticsModule}'s registry is not loaded during plugin setup; first use is
     * long after assets are up. Parts must be vetted for entitlements BY THE ADMIN before
     * adding: the server cannot check them (there is no entitlement data server-side), and
     * edition-locked parts must not be handed out.
     */
    private static final Map<String, String> COSTUME_JSON = new ConcurrentHashMap<>();
    private static final Map<String, PlayerSkin> COSTUME_PARSED = new ConcurrentHashMap<>();

    /** The account skin each player had before their first costume, for restore. */
    private static final Map<UUID, PlayerSkin> ORIGINAL_SKINS = new ConcurrentHashMap<>();

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

    /**
     * Restores. World thread only.
     *
     * <p>Two pushes, because the cached model was never the full appearance. The live
     * {@code ModelComponent} only ever held the base body plus face attachments — hair and
     * clothing are composed CLIENT-side from the {@code PlayerSkin}, which is why the first
     * live test of "off" returned the player's colour and nothing else. The client renders that
     * composition until a model update arrives and switches it to model-only. So restore pushes
     * the cached model back (colour) AND marks the {@code PlayerSkinComponent} network-outdated,
     * on the theory that a fresh skin update flips the client back to composing the full look.
     * If that theory fails live, relogging remains the always-correct restore, and the command
     * says so either way.
     */
    public static Result restore(Store<EntityStore> store, Ref<EntityStore> ref, UUID uuid) {
        boolean restoredAnything = false;

        Model original = ORIGINALS.remove(uuid);
        if (original != null) {
            store.putComponent(ref, ModelComponent.getComponentType(),
                    new ModelComponent(original));
            restoredAnything = true;
        }

        PlayerSkinComponent skin =
                store.getComponent(ref, PlayerSkinComponent.getComponentType());
        PlayerSkin originalSkin = ORIGINAL_SKINS.remove(uuid);
        if (skin != null && skin.getPlayerSkin() != null && originalSkin != null) {
            copyInto(originalSkin, skin.getPlayerSkin());
            restoredAnything = true;
        }
        if (skin != null && (restoredAnything || originalSkin != null)) {
            skin.setNetworkOutdated();
        }

        if (!restoredAnything) {
            return new Result(false,
                    "nothing to restore in this session — relogging restores your look");
        }
        return new Result(true, "restored");
    }

    /** Drops both restore caches without pushing — at login, the account rebuild is the truth. */
    public static void forget(UUID uuid) {
        ORIGINALS.remove(uuid);
        ORIGINAL_SKINS.remove(uuid);
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
