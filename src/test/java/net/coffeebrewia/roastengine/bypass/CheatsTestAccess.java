package net.coffeebrewia.roastengine.bypass;

import java.util.List;

/** Builds a {@link Cheats} straight from an API, without installing mods, for the tests. */
final class CheatsTestAccess {

    private CheatsTestAccess() {
    }

    static Cheats of(BypassApi api, HackClient client) {
        return Cheats.forTesting(api, List.of(client));
    }
}
