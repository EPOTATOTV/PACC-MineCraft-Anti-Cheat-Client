package com.potatotv.pacc.geyser.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleYamlTest {

    @Test
    void parsesScalarsOfVariousTypes() {
        Map<String, Object> map = SimpleYaml.parse("""
                punishment-mode: 0
                confidence-threshold: 60
                max-reach-distance: 3.1
                device-threshold-adjust: true
                server-id: "testserver:25565"
                """);
        assertEquals(0, map.get("punishment-mode"));
        assertEquals(60, map.get("confidence-threshold"));
        assertEquals(3.1, (Double) map.get("max-reach-distance"), 1e-9);
        assertEquals(Boolean.TRUE, map.get("device-threshold-adjust"));
        assertEquals("testserver:25565", map.get("server-id"));
    }

    @Test
    void parsesNestedMap() {
        Map<String, Object> map = SimpleYaml.parse("""
                ptv:
                  url: https://ptv.example
                  secret: s3cr3t
                  server-id: ""
                """);
        Object ptv = map.get("ptv");
        assertTrue(ptv instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) ptv;
        assertEquals("https://ptv.example", nested.get("url"));
        assertEquals("s3cr3t", nested.get("secret"));
        assertEquals("", nested.get("server-id"));
    }

    @Test
    void parsesStringList() {
        Map<String, Object> map = SimpleYaml.parse("""
                enabled-checks:
                  - MOVE
                  - combat
                server-id: id
                """);
        assertEquals(List.of("MOVE", "combat"), map.get("enabled-checks"));
        assertEquals("id", map.get("server-id"));
    }

    @Test
    void stripsCommentsAndBlankLines() {
        Map<String, Object> map = SimpleYaml.parse("""
                # leading comment
                punishment-mode: 1 # trailing comment

                ptv:
                  url: "" # empty
                """);
        assertEquals(1, map.get("punishment-mode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ptv = (Map<String, Object>) map.get("ptv");
        assertEquals("", ptv.get("url"));
    }

    @Test
    void emptyInputYieldsEmptyMap() {
        assertTrue(SimpleYaml.parse("").isEmpty());
        assertTrue(SimpleYaml.parse(null).isEmpty());
    }
}
