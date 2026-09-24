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
import static org.hamcrest.Matchers.startsWith;
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
        Files.createDirectory(dataDir.resolve("media"));
        Files.writeString(dataDir.resolve("media/clip.mp4"), "0123456789");
        Files.writeString(dataDir.resolve("media/page.html"), "<script>alert(1)</script>");
        Files.writeString(dataDir.resolve("media/doc.pdf"), "%PDF-1.4");
        Files.writeString(dataDir.resolve("media/README.md"), "# Media\n\n<b>raw</b>");
        Files.writeString(dataDir.resolve("media/фото заката.png"), "png");
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
    void raw_servesInlineWithRealContentType() throws Exception {
        mockMvc.perform(get("/raw/media/clip.mp4"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("inline;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void raw_supportsRangeRequests() throws Exception {
        mockMvc.perform(get("/raw/media/clip.mp4").header(HttpHeaders.RANGE, "bytes=5-"))
                .andExpect(status().isPartialContent())
                .andExpect(content().string("56789"));
    }

    @Test
    void raw_sandboxesActiveContent() throws Exception {
        mockMvc.perform(get("/raw/media/page.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "sandbox"));
    }

    @Test
    void raw_doesNotSandboxPdf() throws Exception {
        mockMvc.perform(get("/raw/media/doc.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().doesNotExist("Content-Security-Policy"));
    }

    @Test
    void raw_rejectsDirectories() throws Exception {
        mockMvc.perform(get("/raw/media"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void markdown_rendersEscapedHtml() throws Exception {
        mockMvc.perform(get("/markdown/media/README.md"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(containsString("<h1>Media</h1>")))
                .andExpect(content().string(containsString("&lt;b&gt;raw&lt;/b&gt;")));
    }

    @Test
    void markdown_hidesHiddenFiles() throws Exception {
        mockMvc.perform(get("/markdown/.git/credentials.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ui_containsLinkPreviewTags() throws Exception {
        mockMvc.perform(get("/ui/media/фото заката.png")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "files.example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<meta property=\"og:title\" content=\"фото заката.png\">")))
                .andExpect(content().string(containsString(
                        "<meta property=\"og:image\" content=\"https://files.example.com/raw/media/"
                                + "%D1%84%D0%BE%D1%82%D0%BE%20%D0%B7%D0%B0%D0%BA%D0%B0%D1%82%D0%B0.png\">")));
    }

    @Test
    void ui_doesNotPreviewHiddenFiles() throws Exception {
        mockMvc.perform(get("/ui/.git/credentials.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<meta property=\"og:title\" content=\"openjfs\">")))
                .andExpect(content().string(containsString("<meta property=\"og:description\" content=\"File server\">")));
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
