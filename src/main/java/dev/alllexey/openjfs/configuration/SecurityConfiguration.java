package dev.alllexey.openjfs.configuration;

import dev.alllexey.openjfs.security.CurrentUser;
import dev.alllexey.openjfs.security.LoginAttemptService;
import dev.alllexey.openjfs.security.LoginGuardFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.firewall.StrictHttpFirewall;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    public static final String ADMIN_USERNAME = "admin";

    private static final String LOGIN_URL = "/api/login";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, MainConfigurationProperties properties,
                                                   LoginAttemptService loginAttemptService) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole(CurrentUser.ADMIN_ROLE)
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginPage("/ui")
                        .loginProcessingUrl(LOGIN_URL)
                        .successHandler((request, response, authentication) -> {
                            loginAttemptService.recordSuccess(request.getRemoteAddr());
                            log.info("Admin logged in from {}", request.getRemoteAddr());
                            response.setStatus(HttpStatus.NO_CONTENT.value());
                        })
                        .failureHandler((request, response, exception) -> {
                            loginAttemptService.recordFailure(request.getRemoteAddr());
                            log.warn("Failed admin login from {}", request.getRemoteAddr());
                            response.sendError(HttpStatus.UNAUTHORIZED.value());
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        // the web UI embeds pdf files from /raw
                        .frameOptions(frame -> frame.sameOrigin())
                        // set by the reverse proxy, which knows whether https is used
                        .httpStrictTransportSecurity(hsts -> hsts.disable()))
                .addFilterBefore(new LoginGuardFilter(LOGIN_URL, properties, loginAttemptService),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public UserDetailsService userDetailsService(MainConfigurationProperties properties, PasswordEncoder encoder) {
        if (!properties.isAdminEnabled()) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(User.withUsername(ADMIN_USERNAME)
                .password(encoder.encode(properties.getAdminPassword()))
                .roles(CurrentUser.ADMIN_ROLE)
                .build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // file names may contain '%', which the default firewall rejects in encoded form
    @Bean
    public WebSecurityCustomizer httpFirewallCustomizer() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedPercent(true);
        return web -> web.httpFirewall(firewall);
    }
}
