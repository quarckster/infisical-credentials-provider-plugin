package io.jenkins.plugins.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-unit coverage of base-URL normalization and project-id classification (no Jenkins needed). */
class InfisicalClientUrlTest {

    @Test
    void normalizesTrailingSlashesAndApiSuffix() {
        assertEquals("https://infisical.example.com",
                InfisicalClient.normalizeBaseUrl("https://infisical.example.com"));
        assertEquals("https://infisical.example.com",
                InfisicalClient.normalizeBaseUrl("https://infisical.example.com/"));
        assertEquals("https://infisical.example.com",
                InfisicalClient.normalizeBaseUrl("https://infisical.example.com///"));
        assertEquals("https://infisical.example.com",
                InfisicalClient.normalizeBaseUrl("  https://infisical.example.com/api  "));
        assertEquals("https://infisical.example.com",
                InfisicalClient.normalizeBaseUrl("https://infisical.example.com/api/"));
    }

    @Test
    void classifiesUuidVsSlug() {
        assertTrue(InfisicalClient.isUuid("1c1c1c1c-2d2d-3e3e-4f4f-555566667777"));
        assertFalse(InfisicalClient.isUuid("my-project"));
        assertFalse(InfisicalClient.isUuid(null));
        assertFalse(InfisicalClient.isUuid("not-a-uuid"));
    }
}
