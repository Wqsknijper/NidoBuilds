package nl.nidocraft.builds.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildGameRulesTest {
    @Test void requiredBuildDefaultsAreStable() {
        assertEquals("false", BuildGameRules.defaults().get("doWeatherCycle"));
        assertEquals("false", BuildGameRules.defaults().get("doDaylightCycle"));
        assertEquals("0", BuildGameRules.defaults().get("randomTickSpeed"));
        assertEquals("false", BuildGameRules.defaults().get("doMobSpawning"));
        assertEquals("false", BuildGameRules.defaults().get("doMobLoot"));
        assertEquals("false", BuildGameRules.defaults().get("announceAdvancements"));
        assertEquals("false", BuildGameRules.defaults().get("doFireTick"));
        assertEquals("false", BuildGameRules.defaults().get("spectatorsGenerateChunks"));
        assertEquals(8, BuildGameRules.defaults().size());
    }
}
