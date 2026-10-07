package com.yuntian.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 管理接口的服务间令牌校验。 */
@Component
@ConditionalOnProperty(name = "admin.internal.enabled", havingValue = "true")
public class AdminInternalAuthFilter extends OncePerRequestFilter {
    @Value("${admin.internal.token:}") private String expectedToken;

    @PostConstruct
    public void validateConfiguration() {
        if (expectedToken.isBlank()) {
            throw new IllegalStateException("启用 Agent 管理接口时必须配置 ADMIN_INTERNAL_TOKEN");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/internal/admin/")
                && !path.equals("/internal/memory/l1-prompt/init");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String received = request.getHeader("X-Admin-Internal-Token");
        boolean matches = received != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
