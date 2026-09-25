package dev.alllexey.openjfs.security;

import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// rejects login attempts before the password is checked: when admin mode is off or the client is blocked
@RequiredArgsConstructor
public class LoginGuardFilter extends OncePerRequestFilter {

    private final String loginUrl;

    private final MainConfigurationProperties properties;

    private final LoginAttemptService loginAttemptService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // servlet path is empty in some setups, the uri is reliable
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !("POST".equals(request.getMethod()) && loginUrl.equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isAdminEnabled()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        if (loginAttemptService.isBlocked(request.getRemoteAddr())) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(LoginAttemptService.BLOCK_DURATION.toSeconds()));
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }
        chain.doFilter(request, response);
    }
}
