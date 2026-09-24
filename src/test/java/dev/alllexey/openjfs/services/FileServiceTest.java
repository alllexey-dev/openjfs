package dev.alllexey.openjfs.services;

import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.model.DirectoryInfo;
import dev.alllexey.openjfs.model.FileInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileServiceTest {

    @TempDir
    Path tempDir;

    private Path dataDir;

    private Path outsideDir;

    private MainConfigurationProperties properties;

    private FileService fileService;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createDirectory(tempDir.resolve("data"));
        outsideDir = Files.createDirectory(tempDir.resolve("outside"));

        Files.createDirectories(dataDir.resolve("docs/sub dir"));
        Files.writeString(dataDir.resolve("docs/отчёт.txt"), "report");
        Files.writeString(dataDir.resolve("docs/sub dir/report copy.txt"), "copy");
        Files.createDirectory(dataDir.resolve(".git"));
        Files.writeString(dataDir.resolve(".git/report-secret.txt"), "secret");
        Files.writeString(outsideDir.resolve("report-outside.txt"), "outside");
        Files.createSymbolicLink(dataDir.resolve("escape"), outsideDir);
        Files.createSymbolicLink(dataDir.resolve("docs-link"), dataDir.resolve("docs"));

        properties = new MainConfigurationProperties();
        properties.setDataPath(dataDir.toString());
        properties.setAllowHidden(false);
        properties.setZipCompressionLevel(1);
        properties.setSearchMaxResults(1000);
        fileService = new FileService(properties);
    }

    @Test
    void checkAccess_allowsRegularFile() {
        Path path = fileService.resolveRequestedPath("/docs/отчёт.txt");

        assertThat(fileService.checkAccess(path)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void checkAccess_forbidsPathTraversal() {
        Path path = fileService.resolveRequestedPath("/../outside/report-outside.txt");

        assertThat(fileService.checkAccess(path)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void checkAccess_forbidsSymlinkPointingOutside() {
        Path path = fileService.resolveRequestedPath("/escape/report-outside.txt");

        assertThat(fileService.checkAccess(path)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void checkAccess_allowsSymlinkPointingInside() {
        Path path = fileService.resolveRequestedPath("/docs-link/отчёт.txt");

        assertThat(fileService.checkAccess(path)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void checkAccess_hidesHiddenFilesWhenDisabled() {
        Path path = fileService.resolveRequestedPath("/.git/report-secret.txt");

        assertThat(fileService.checkAccess(path)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void checkAccess_allowsHiddenFilesWhenEnabled() {
        properties.setAllowHidden(true);
        Path path = fileService.resolveRequestedPath("/.git/report-secret.txt");

        assertThat(fileService.checkAccess(path)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void checkAccess_worksWithRelativeDataPath() {
        Path relativeDataPath = Path.of("").toAbsolutePath().relativize(dataDir);
        properties.setDataPath("./" + relativeDataPath);
        Path path = fileService.resolveRequestedPath("/docs/отчёт.txt");

        assertThat(fileService.checkAccess(path)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resolveRequestedPath_rejectsInvalidPath() {
        assertThatThrownBy(() -> fileService.resolveRequestedPath("/bad\0name"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getFileInfo_listsOnlyAccessibleChildren() {
        DirectoryInfo root = (DirectoryInfo) fileService.getFileInfo(dataDir, 1);

        assertThat(root.getFiles()).extracting(FileInfo::getName)
                .containsExactlyInAnyOrder("docs", "docs-link");
    }

    @Test
    void getFileInfo_treatsDirectoryWithOnlyHiddenFilesAsEmpty() throws IOException {
        Files.createDirectory(dataDir.resolve("only-hidden"));
        Files.writeString(dataDir.resolve("only-hidden/.env"), "x");

        DirectoryInfo dir = (DirectoryInfo) fileService.getFileInfo(dataDir.resolve("only-hidden"), 0);

        assertThat(dir.isEmpty()).isTrue();
    }

    @Test
    void simpleSearch_skipsHiddenDirectoriesAndEscapingSymlinks() {
        List<FileInfo> result = fileService.simpleSearch(dataDir, "REPORT");

        assertThat(result).extracting(fi -> fi.getPath() + fi.getName())
                .containsExactlyInAnyOrder(
                        "docs/sub dir/report copy.txt",
                        "docs-link/sub dir/report copy.txt"
                );
    }

    @Test
    void simpleSearch_doesNotReturnSearchRoot() {
        List<FileInfo> result = fileService.simpleSearch(dataDir.resolve("docs"), "docs");

        assertThat(result).isEmpty();
    }

    @Test
    void simpleSearch_stopsAtMaxResults() {
        properties.setSearchMaxResults(1);

        List<FileInfo> result = fileService.simpleSearch(dataDir, "report");

        assertThat(result).hasSize(1);
    }

    @Test
    void zipDirectory_containsOnlyAccessibleEntries() throws IOException {
        properties.setAllowHidden(false);

        List<String> entries = zipEntries(dataDir);

        assertThat(entries).containsExactlyInAnyOrder(
                "docs/", "docs/sub dir/", "docs/sub dir/report copy.txt", "docs/отчёт.txt",
                "docs-link/", "docs-link/sub dir/", "docs-link/sub dir/report copy.txt", "docs-link/отчёт.txt"
        );
    }

    @Test
    void zipDirectory_doesNotHangOnNamedPipe() throws Exception {
        Path fifo = dataDir.resolve("docs/pipe");
        boolean created = new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0;
        assumeTrue(created, "mkfifo is not available");

        List<String> entries = CompletableFuture.supplyAsync(() -> {
            try {
                return zipEntries(dataDir.resolve("docs"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);

        assertThat(entries).doesNotContain("pipe");
    }

    private List<String> zipEntries(Path dir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fileService.zipDirectory(dir, out);

        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
