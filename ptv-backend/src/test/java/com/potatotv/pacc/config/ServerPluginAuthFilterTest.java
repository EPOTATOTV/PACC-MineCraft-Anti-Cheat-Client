package com.potatotv.pacc.config;

import com.potatotv.pacc.util.ApiSignature;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端插件通道鉴权：只接管 /api/v1/server/**，其余路径放行；
 * 密钥缺失（fail-closed）、签名不符一律 401，签名正确才继续。
 */
class ServerPluginAuthFilterTest {

    private static final String SECRET = "pacc-server-plugin-test-secret";

    @Test
    void onlyServerPrefixIsIntercepted() {
        ServerPluginAuthFilter filter = new ServerPluginAuthFilter(SECRET);
        assertFalse(filter.shouldNotFilter(req("/api/v1/server/register", new byte[0])));
        assertTrue(filter.shouldNotFilter(req("/api/v1/detections", new byte[0])));
        assertTrue(filter.shouldNotFilter(req("/api/admin/overview", new byte[0])));
        assertTrue(filter.shouldNotFilter(req("/api/v1/server", new byte[0])));
    }

    @Test
    void validSignaturePasses() throws Exception {
        String body = "{\"serverId\":\"s1\"}";
        MockHttpServletRequest request = req("/api/v1/server/register", body.getBytes(StandardCharsets.UTF_8));
        request.addHeader(ServerPluginAuthFilter.SIGNATURE_HEADER, ApiSignature.hmacHex(SECRET, body));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (rq, rs) -> invoked.set(true);

        new ServerPluginAuthFilter(SECRET).doFilterInternal(request, response, chain);

        assertTrue(invoked.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void badSignatureRejected() throws Exception {
        MockHttpServletRequest request = req("/api/v1/server/event", "{}".getBytes(StandardCharsets.UTF_8));
        request.addHeader(ServerPluginAuthFilter.SIGNATURE_HEADER, "deadbeef");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (rq, rs) -> invoked.set(true);

        new ServerPluginAuthFilter(SECRET).doFilterInternal(request, response, chain);

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void blankSecretIsFailClosed() throws Exception {
        MockHttpServletRequest request = req("/api/v1/server/register", "{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (rq, rs) -> invoked.set(true);

        new ServerPluginAuthFilter("").doFilterInternal(request, response, chain);

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    private static MockHttpServletRequest req(String uri, byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setContent(content);
        return request;
    }
}
