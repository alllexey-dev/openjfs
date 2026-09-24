package dev.alllexey.openjfs.services;

import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.model.LinkPreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LinkPreviewServiceTest {

    @TempDir
    Path dataDir;

    private LinkPreviewService linkPreviewService;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(dataDir.resolve("mods/client"));
        Path archive = Files.write(dataDir.resolve("mods/modpack.zip"), new byte[3 * 1024 * 1024 + 100]);
        Files.setLastModifiedTime(archive, FileTime.from(
                LocalDateTime.of(2026, 8, 7, 12, 0).atZone(ZoneId.systemDefault()).toInstant()));
        Files.writeString(dataDir.resolve("mods/screenshot.png"), "png");
        Files.writeString(dataDir.resolve("mods/logo.svg"), "<svg/>");
        Files.createDirectory(dataDir.resolve(".secret"));
        Files.writeString(dataDir.resolve(".secret/token.txt"), "token");

        MainConfigurationProperties properties = new MainConfigurationProperties();
        properties.setDataPath(dataDir.toString());
        properties.setServerName("alllexey files");
        linkPreviewService = new LinkPreviewService(properties, new FileService(properties));
    }

    @Test
    void preview_describesFile() {
        LinkPreview preview = linkPreviewService.preview("/mods/modpack.zip");

        assertThat(preview.title()).isEqualTo("modpack.zip");
        assertThat(preview.pageTitle()).isEqualTo("modpack.zip · alllexey files");
        assertThat(preview.description()).isEqualTo("3.0 MB · Aug 7, 2026");
        assertThat(preview.imagePath()).isEmpty();
    }

    @Test
    void preview_describesFolderContents() {
        LinkPreview preview = linkPreviewService.preview("/mods");

        assertThat(preview.title()).isEqualTo("mods");
        assertThat(preview.description()).isEqualTo("Folder · 1 folder, 3 files");
    }

    @Test
    void preview_usesServerNameForRoot() {
        LinkPreview preview = linkPreviewService.preview("/");

        assertThat(preview.title()).isEqualTo("alllexey files");
        assertThat(preview.pageTitle()).isEqualTo("alllexey files");
        assertThat(preview.description()).isEqualTo("Folder · 1 folder");
    }

    @Test
    void preview_includesImageForRasterImages() {
        LinkPreview preview = linkPreviewService.preview("/mods/screenshot.png");

        assertThat(preview.imagePath()).contains("mods/screenshot.png");
    }

    @Test
    void preview_skipsImageForSvg() {
        LinkPreview preview = linkPreviewService.preview("/mods/logo.svg");

        assertThat(preview.imagePath()).isEmpty();
    }

    @Test
    void preview_revealsNothingAboutHiddenFiles() {
        LinkPreview preview = linkPreviewService.preview("/.secret/token.txt");

        assertThat(preview.title()).isEqualTo("alllexey files");
        assertThat(preview.description()).isEqualTo("File server");
    }

    @Test
    void preview_fallsBackToDefaultForInvalidPath() {
        LinkPreview preview = linkPreviewService.preview("/bad\0name");

        assertThat(preview.title()).isEqualTo("alllexey files");
    }
}
