package com.eclassroom.core.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt &&
                Boolean.TRUE.equals(jwt.getToken().getClaim("mustChangePassword")) &&
                !allowed(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"You must change your temporary password before continuing\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(String path) {
        return path.equals("/api/v1/me") ||
                path.equals("/api/v1/account/password") ||
                path.startsWith("/api/v1/account/sessions") ||
                path.equals("/api/v1/auth/logout") ||
                path.startsWith("/actuator/health");
    }
}
