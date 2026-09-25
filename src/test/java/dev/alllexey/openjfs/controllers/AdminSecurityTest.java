package dev.alllexey.openjfs.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminSecurityTest {

    private static final String PASSWORD = "correct horse battery staple";

    @TempDir
    static Path dataDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("openjfs.data-path", dataDir::toString);
        registry.add("openjfs.admin-password", () -> PASSWORD);
    }

    @BeforeAll
    static void createFiles() throws IOException {
        Files.createDirectories(dataDir.resolve("public"));
        Files.createDirectories(dataDir.resolve("secret"));
        Files.createFile(dataDir.resolve("secret/.private"));
        Files.writeString(dataDir.resolve("secret/passwords.txt"), "hunter2");
    }

    @Test
    void adminApi_requiresLogin() throws Exception {
        mockMvc.perform(get("/api/admin/trash"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withSessionCsrfToken_grantsAdminAccess() throws Exception {
        MvcResult sessionResult = mockMvc.perform(get("/api/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminEnabled").value(true))
                .andExpect(jsonPath("$.admin").value(false))
                .andReturn();
        JsonNode session = objectMapper.readTree(sessionResult.getResponse().getContentAsString());
        MockHttpSession httpSession = (MockHttpSession) sessionResult.getRequest().getSession(false);

        mockMvc.perform(post("/api/login")
                        .session(httpSession)
                        .header(session.get("csrfHeader").asText(), session.get("csrfToken").asText())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", PASSWORD))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/trash").session(httpSession))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/session").session(httpSession))
                .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void login_rejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/login").with(csrf()).with(from("10.1.1.1"))
                        .param("username", "admin")
                        .param("password", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_isBlockedAfterTooManyFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/login").with(csrf()).with(from("10.2.2.2"))
                    .param("username", "admin")
                    .param("password", "wrong-" + i));
        }

        mockMvc.perform(post("/api/login").with(csrf()).with(from("10.2.2.2"))
                        .param("username", "admin")
                        .param("password", PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void upload_requiresCsrfToken() throws Exception {
        mockMvc.perform(put("/api/admin/files/public/no-csrf.txt").with(admin()).content("x"))
                .andExpect(status().isForbidden());

        assertThat(dataDir.resolve("public/no-csrf.txt")).doesNotExist();
    }

    @Test
    void upload_storesFileForAdmin() throws Exception {
        mockMvc.perform(put("/api/admin/files/public/мод.jar").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("jar"))
                .andExpect(status().isCreated());

        assertThat(dataDir.resolve("public/мод.jar")).hasContent("jar");
    }

    @Test
    void privateFolder_isInvisibleToVisitors() throws Exception {
        mockMvc.perform(get("/list/secret")).andExpect(status().isNotFound());
        mockMvc.perform(get("/direct/secret/passwords.txt")).andExpect(status().isNotFound());
        mockMvc.perform(get("/raw/secret/passwords.txt")).andExpect(status().isNotFound());
        mockMvc.perform(get("/search/").param("q", "passwords"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/list/"))
                .andExpect(jsonPath("$.files[?(@.name == 'secret')]").isEmpty());
    }

    @Test
    void privateFolder_isVisibleToAdmin() throws Exception {
        mockMvc.perform(get("/list/").with(admin()))
                .andExpect(jsonPath("$.files[?(@.name == 'secret')].private").value(true));
        mockMvc.perform(get("/direct/secret/passwords.txt").with(admin()))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
