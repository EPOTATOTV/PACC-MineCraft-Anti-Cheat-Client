package com.potatotv.pacc.configcenter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ConfigCenterProperties} 默认值与绑定单测。 */
class ConfigCenterPropertiesTest {

    @Test
    void defaultsAreFailSafeLocal() {
        ConfigCenterProperties p = new ConfigCenterProperties();
        assertEquals("local", p.getProvider());
        assertEquals("application", p.getDefaultNamespace());
        assertFalse(p.isBootstrapEnabled());
        assertTrue(p.getNamespaces().isEmpty());
        assertNull(p.getAppId());
        assertNull(p.getMeta());
    }

    @Test
    void settersRoundTrip() {
        ConfigCenterProperties p = new ConfigCenterProperties();
        p.setProvider("apollo");
        p.setAppId("pacc-ptv");
        p.setMeta("http://apollo-config:8080");
        p.setDefaultNamespace("detection.threshold");
        p.setNamespaces(List.of("detection.rules", "redscreen.policy"));
        p.setBootstrapEnabled(true);

        assertEquals("apollo", p.getProvider());
        assertEquals("pacc-ptv", p.getAppId());
        assertEquals("http://apollo-config:8080", p.getMeta());
        assertEquals("detection.threshold", p.getDefaultNamespace());
        assertEquals(2, p.getNamespaces().size());
        assertTrue(p.isBootstrapEnabled());
    }
}
