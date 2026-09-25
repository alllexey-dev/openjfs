package dev.alllexey.openjfs.controllers;

import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// the web UI asks for the csrf token only before login or admin actions,
// so regular visitors don't get a server session
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class SessionController {

    private final MainConfigurationProperties properties;

    private final CurrentUser currentUser;

    @GetMapping
    public SessionInfo session(CsrfToken csrfToken) {
        return new SessionInfo(properties.isAdminEnabled(), currentUser.isAdmin(),
                csrfToken.getHeaderName(), csrfToken.getToken());
    }

    public record SessionInfo(boolean adminEnabled, boolean admin, String csrfHeader, String csrfToken) {
    }
}
