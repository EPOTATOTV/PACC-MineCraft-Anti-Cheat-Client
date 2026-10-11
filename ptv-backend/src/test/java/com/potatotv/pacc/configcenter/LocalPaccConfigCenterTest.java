package com.potatotv.pacc.configcenter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LocalPaccConfigCenter} 的类型转换与降级默认值单测（不依赖 Spring 容器）。 */
class LocalPaccConfigCenterTest {

    private MockEnvironment env() {
        return new MockEnvironment();
    }

    @Test
    void providerIsLocalAndAlwaysAvailable() {
        LocalPaccConfigCenter cc = new LocalPaccConfigCenter(env());
        assertEquals("local", cc.provider());
        assertTrue(cc.available());
    }

    @Test
    void getStringReturnsValueOrDefault() {
        MockEnvironment env = env();
        env.setProperty("pacc.detection.redscreen-threshold", "85");
        LocalPaccConfigCenter cc = new LocalPaccConfigCenter(env);
        assertEquals("85", cc.getString("pacc.detection.redscreen-threshold", "0"));
        assertEquals("fallback", cc.getString("missing.key", "fallback"));
    }

    @Test
    void getIntCoercesStringAndFallsBack() {
        MockEnvironment env = env();
        env.setProperty("pacc.rules.max-bonus", "15");
        LocalPaccConfigCenter cc = new LocalPaccConfigCenter(env);
        assertEquals(15, cc.getInt("pacc.rules.max-bonus", 1));
        assertEquals(1, cc.getInt("missing.key", 1));
    }

    @Test
    void getDoubleCoercesStringAndFallsBack() {
        MockEnvironment env = env();
        env.setProperty("pacc.zero-day.recon-scale", "1.2");
        LocalPaccConfigCenter cc = new LocalPaccConfigCenter(env);
        assertEquals(1.2, cc.getDouble("pacc.zero-day.recon-scale", 0.0), 1e-9);
        assertEquals(3.0, cc.getDouble("missing.key", 3.0), 1e-9);
    }

    @Test
    void getBooleanCoercesStringAndFallsBack() {
        MockEnvironment env = env();
        env.setProperty("pacc.rules.enabled", "true");
        LocalPaccConfigCenter cc = new LocalPaccConfigCenter(env);
        assertTrue(cc.getBoolean("pacc.rules.enabled", false));
        assertFalse(cc.getBoolean("missing.key", false));
        assertTrue(cc.getBoolean("missing.key", true));
    }

    @Test
    void namespaceIsEmptyAndListenerIsNoop() {
        LocalPaccConfigCenter cc = new LocalPaccConfigCenter(env());
        assertTrue(cc.namespace("application").isEmpty());
        AtomicBoolean called = new AtomicBoolean(false);
        cc.addChangeListener("application", keys -> called.set(true));
        assertFalse(called.get());
    }
}
