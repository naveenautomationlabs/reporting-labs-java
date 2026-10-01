package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class EnvDetectTest {
    private static String d(Map<String, String> env) { return Config.detectedEnv(env, new Properties(), null); }

    @Test void usualNames() {
        assertEquals("dev", d(Map.of("ENV", "dev")));
        assertEquals("app_qa", d(Map.of("APP_ENV", "app_qa")));
        assertEquals("staging", d(Map.of("CI_ENVIRONMENT_NAME", "staging")));
        assertEquals("dev", d(Map.of("ENV", "dev", "APP_ENV", "qa")));   // the first known name wins
    }

    @Test void anyProjectSpecificNameEndingInEnv() {
        assertEquals("stage-2", d(Map.of("OPENCART_ENV", "stage-2")));
        assertEquals("uat", d(Map.of("app_env", "uat")));
        assertEquals("prod", d(Map.of("GITHUB_ENV", "/tmp/x", "MY_TARGET_ENV", "prod")));
    }

    @Test void systemVariablesAndJunkValuesAreIgnored() {
        assertNull(d(Map.of("GITHUB_ENV", "/home/runner/work/_temp/set_env_1", "NODE_ENV", "production", "VIRTUAL_ENV", "/x/venv")));
        assertNull(d(Map.of("ENV", "this is a sentence")));
        assertNull(d(Map.of()));
    }

    @Test void explicitEnvVarAndSystemPropertiesWin() {
        assertEquals("preprod", Config.detectedEnv(Map.of("ENV", "dev", "WHATEVER", "preprod"), new Properties(), "WHATEVER"));
        Properties p = new Properties(); p.setProperty("env", "uat");
        assertEquals("uat", Config.detectedEnv(Map.of("ENV", "dev"), p, null));
    }
}
