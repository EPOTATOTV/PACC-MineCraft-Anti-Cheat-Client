package com.potatotv.pacc.config;

import com.potatotv.pacc.util.ApiSignature;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 服务端插件上报通道（{@code /api/v1/server/**}）鉴权。
 *
 * <p>服务端插件跑在运维自己的服务器进程里，凭据是一枚共享密钥：对请求体做
 * {@code HMAC-SHA256(secret, rawBody)}，十六进制放入 {@code X-PACC-Signature} 头。
 * 与玩家端开放 API（{@code X-PTV-Key} + 时间窗 + nonce）是两套独立凭据——服务端插件不需要
 * Key 管理，一个服务器一条注册记录即可。</p>
 *
 * <p>fail-closed：密钥未配置时一律 401，绝不静默放行。签名只覆盖请求体，因此这里自带可重复读的
 * wrapper，既能在过滤器里算签名，又能让下游 {@code @RequestBody} 正常解析。</p>
 */
public class ServerPluginAuthFilter extends OncePerRequestFilter {

    public static final String SIGNATURE_HEADER = "X-PACC-Signature";
    private static final String PATH_PREFIX = "/api/v1/server/";

    private final String secret;

    public ServerPluginAuthFilter(String secret) {
        this.secret = secret == null ? "" : secret.trim();
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (secret.isBlank()) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"服务端插件通道未启用\"}");
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        String provided = request.getHeader(SIGNATURE_HEADER);
        String expected = ApiSignature.hmacHex(secret, new String(body, StandardCharsets.UTF_8));
        if (provided == null || !ApiSignature.constantTimeEquals(provided.trim(), expected)) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"签名校验失败\"}");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    /** 把已读取的请求体缓存下来，供下游反复读取。 */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // 同步读，无需异步回调
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
