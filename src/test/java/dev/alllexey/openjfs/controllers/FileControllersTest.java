package dev.alllexey.openjfs.controllers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FileControllersTest {

    @TempDir
    static Path dataDir;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("openjfs.data-path", dataDir::toString);
    }

    @BeforeAll
    static void createFiles() throws IOException {
        Files.createDirectories(dataDir.resolve("Мои документы"));
        Files.writeString(dataDir.resolve("Мои документы/отчёт \"итог\".txt"), "0123456789");
        Files.createDirectory(dataDir.resolve(".git"));
        Files.writeString(dataDir.resolve(".git/credentials.txt"), "secret");
    }

    @Test
    void direct_encodesNonAsciiFilename() throws Exception {
        mockMvc.perform(get("/direct/Мои документы/отчёт \"итог\".txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(
                        "filename*=UTF-8''%D0%BE%D1%82%D1%87%D1%91%D1%82%20%22%D0%B8%D1%82%D0%BE%D0%B3%22.txt")))
                .andExpect(content().string("0123456789"));
    }

    @Test
    void direct_supportsRangeRequests() throws Exception {
        mockMvc.perform(get("/direct/Мои документы/отчёт \"итог\".txt").header(HttpHeaders.RANGE, "bytes=2-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-4/10"))
                .andExpect(content().string("234"));
    }

    @Test
    void direct_zipsDirectory() throws Exception {
        byte[] zip = mockMvc.perform(get("/direct/Мои документы"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(
                        "filename*=UTF-8''%D0%9C%D0%BE%D0%B8%20%D0%B4%D0%BE%D0%BA%D1%83%D0%BC%D0%B5%D0%BD%D1%82%D1%8B.zip")))
                .andReturn().getResponse().getContentAsByteArray();

        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(zip))) {
            assertThat(zipIn.getNextEntry().getName()).isEqualTo("отчёт \"итог\".txt");
        }
    }

    @Test
    void direct_hidesHiddenFilesByDefault() throws Exception {
        mockMvc.perform(get("/direct/.git/credentials.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void text_returnsUtf8PlainText() throws Exception {
        mockMvc.perform(get("/text/Мои документы/отчёт \"итог\".txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"));
    }

    @Test
    void search_rejectsBlankQuery() throws Exception {
        mockMvc.perform(get("/search/").param("q", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_doesNotRevealFilesInHiddenDirectories() throws Exception {
        mockMvc.perform(get("/search/").param("q", "credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
