package com.wallet.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.dto.ErrorResponse;
import com.wallet.exception.UnauthorizedException;
import com.wallet.metrics.WalletMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-token authentication. The caller's identity is whatever the verified
 * JWT says - a client-supplied header or body field is never trusted. Public
 * routes (token issuing, probes, metrics) skip the filter entirely.
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTR = "wallet.auth.userId";

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private static final Set<String> PUBLIC_PATHS =
            Set.of("/auth/token", "/healthz", "/readyz", "/metrics");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final WalletMetrics metrics;

    public AuthFilter(JwtService jwtService, ObjectMapper objectMapper, WalletMetrics metrics) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, "missing_bearer_token");
            return;
        }
        String userId;
        try {
            userId = jwtService.verify(header.substring("Bearer ".length()));
        } catch (UnauthorizedException e) {
            reject(response, e.getReason());
            return;
        }
        request.setAttribute(USER_ATTR, userId);
        MDC.put("user_id", userId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("user_id");
        }
    }

    private void reject(HttpServletResponse response, String reason) throws IOException {
        metrics.authFailure();
        log.atWarn().setMessage("auth_failure")
                .addKeyValue("reason", reason)
                .log();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorResponse("unauthorized", reason));
    }
}
