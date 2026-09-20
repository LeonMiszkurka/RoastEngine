package net.coffeebrewia.roastengine.bypass;

import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.modding.LocalMod;
import net.coffeebrewia.roastengine.modding.ModManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cheats that are on right now, and the hack client they came from.
 *
 * <p>Built from the installed mods: a {@link BypassApi} that is enabled, plus every enabled
 * {@link HackClient}. With no API, or the API switched off, {@link #isActive()} is false, nothing
 * is read and the game behaves exactly as it does with no mods at all.
 *
 * <p>The sandbox asks this for values ({@link #on}, {@link #value}) and for one-shot actions
 * ({@link #consume}), which keeps the cheating in one place instead of spread through the game.
 */
public final class Cheats {

    private final BypassApi api;
    private final List<HackClient> clients;
    private final Map<String, Float> values = new HashMap<>();
    private final Set<String> pending = new HashSet<>();

    private Cheats(BypassApi api, List<HackClient> clients) {
        this.api = api;
        this.clients = clients;
        for (Cheat cheat : Cheat.ALL) {
            if (api != null && api.allows(cheat.id())) {
                values.put(cheat.id(), cheat.initial());
            }
        }
        for (HackClient client : clients) {
            client.defaults().forEach((id, value) -> {
                if (values.containsKey(id)) {
                    values.put(id, clamp(Cheat.find(id), value));
                }
            });
        }
    }

    /** Loads the Bypass API and any hack clients the player has enabled. */
    public static Cheats load(ModManager mods) {
        BypassApi api = mods.enabledApis().stream()
                .map(LocalMod::folder)
                .filter(BypassApi::isBypassApi)
                .map(BypassApi::load)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (api == null) {
            return new Cheats(null, List.of());
        }
        List<HackClient> clients = new ArrayList<>();
        for (LocalMod mod : mods.installedMods()) {
            if (!mod.isApi() && mods.isEnabled(mod) && HackClient.isHackClient(mod.folder())) {
                HackClient client = HackClient.load(mod.folder(), mod.name(), api);
                if (client != null) {
                    clients.add(client);
                }
            }
        }
        return new Cheats(api, List.copyOf(clients));
    }

    /** Used by the tests to build a set of cheats without installing mods. */
    static Cheats forTesting(BypassApi api, List<HackClient> clients) {
        return new Cheats(api, clients);
    }

    /** False when there is no Bypass API enabled, or no hack client using it. */
    public boolean isActive() {
        return api != null && !clients.isEmpty();
    }

    public List<HackClient> clients() {
        return clients;
    }

    public String apiName() {
        return api == null ? "" : api.name();
    }

    /** True while this switch is on. Always false for anything the API doesn't allow. */
    public boolean on(String cheatId) {
        return isActive() && values.getOrDefault(cheatId, 0f) > 0.5f;
    }

    /** The current number, or {@code fallback} when this cheat isn't available. */
    public float value(String cheatId, float fallback) {
        return isActive() && values.containsKey(cheatId) ? values.get(cheatId) : fallback;
    }

    public boolean has(String cheatId) {
        return isActive() && values.containsKey(cheatId);
    }

    public void set(String cheatId, float value) {
        if (values.containsKey(cheatId)) {
            values.put(cheatId, clamp(Cheat.find(cheatId), value));
        }
    }

    public void toggle(String cheatId) {
        set(cheatId, on(cheatId) ? 0f : 1f);
    }

    /** Fires a one-shot cheat, such as a teleport; the sandbox picks it up with {@link #consume}. */
    public void trigger(String cheatId) {
        if (values.containsKey(cheatId)) {
            pending.add(cheatId);
        }
    }

    /** True once after {@link #trigger}, for the sandbox to act on. */
    public boolean consume(String cheatId) {
        return pending.remove(cheatId);
    }

    /** Handles the hotkeys a hack client binds. Call once a frame while playing. */
    public void handleHotkeys(Input input) {
        if (!isActive()) {
            return;
        }
        for (HackClient client : clients) {
            client.binds().forEach((id, key) -> {
                if (input.wasKeyPressed(key)) {
                    Cheat cheat = Cheat.find(id);
                    if (cheat != null && cheat.kind() == Cheat.Kind.ACTION) {
                        trigger(id);
                    } else {
                        toggle(id);
                    }
                }
            });
        }
    }

    /** The key that opens the menu, from the first hack client; -1 when there is none. */
    public int menuKey() {
        return clients.isEmpty() ? -1 : clients.get(0).menuKey();
    }

    /** The menu key's name, for telling the player which key opens it. */
    public String menuKeyName() {
        return Keys.name(menuKey());
    }

    private static float clamp(Cheat cheat, float value) {
        if (cheat == null) {
            return value;
        }
        if (cheat.kind() == Cheat.Kind.TOGGLE) {
            return value > 0.5f ? 1f : 0f;
        }
        return Math.max(cheat.min(), Math.min(cheat.max(), value));
    }
}
