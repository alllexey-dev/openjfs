package dev.alllexey.openjfs.services;

import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.model.FileType;
import dev.alllexey.openjfs.model.TrashEntry;
import dev.alllexey.openjfs.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminFileServiceTest {

    @TempDir
    Path dataDir;

    @TempDir
    Path outsideDir;

    private AdminFileService adminFileService;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(dataDir.resolve("mods/client"));
        Files.writeString(dataDir.resolve("mods/modpack.zip"), "old");

        MainConfigurationProperties properties = new MainConfigurationProperties();
        properties.setDataPath(dataDir.toString());
        CurrentUser admin = mock(CurrentUser.class);
        when(admin.isAdmin()).thenReturn(true);
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);
        adminFileService = new AdminFileService(properties, new FileService(properties, admin), clock);
    }

    @Test
    void upload_createsFile() throws IOException {
        adminFileService.upload("/mods/новый мод.jar", content("jar"), 3, false);

        assertThat(dataDir.resolve("mods/новый мод.jar")).hasContent("jar");
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void upload_rejectsExistingFileWithoutOverwrite() {
        assertStatus(() -> adminFileService.upload("/mods/modpack.zip", content("new"), 3, false),
                HttpStatus.CONFLICT);
        assertThat(dataDir.resolve("mods/modpack.zip")).hasContent("old");
    }

    @Test
    void upload_replacesExistingFileWithOverwrite() throws IOException {
        adminFileService.upload("/mods/modpack.zip", content("new"), 3, true);

        assertThat(dataDir.resolve("mods/modpack.zip")).hasContent("new");
    }

    @Test
    void upload_discardsInterruptedUpload() {
        assertStatus(() -> adminFileService.upload("/mods/big.zip", content("par"), 100, false),
                HttpStatus.BAD_REQUEST);
        assertThat(dataDir.resolve("mods/big.zip")).doesNotExist();
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void upload_requiresExistingFolder() {
        assertStatus(() -> adminFileService.upload("/missing/file.txt", content("x"), 1, false),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void upload_rejectsReservedAndHiddenNames() {
        assertStatus(() -> adminFileService.upload("/mods/.private", content("x"), 1, false),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> adminFileService.upload("/mods/.env", content("x"), 1, false),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void upload_rejectsPathOutsideDataDir() {
        assertStatus(() -> adminFileService.upload("/../escape.txt", content("x"), 1, false),
                HttpStatus.BAD_REQUEST);
        assertThat(dataDir.resolveSibling("escape.txt")).doesNotExist();
    }

    @Test
    void createFolder_createsFolderOnce() throws IOException {
        adminFileService.createFolder("/mods/server");

        assertThat(dataDir.resolve("mods/server")).isDirectory();
        assertStatus(() -> adminFileService.createFolder("/mods/server"), HttpStatus.CONFLICT);
    }

    @Test
    void move_renamesFile() throws IOException {
        adminFileService.move("/mods/modpack.zip", "/mods/modpack-v2.zip");

        assertThat(dataDir.resolve("mods/modpack.zip")).doesNotExist();
        assertThat(dataDir.resolve("mods/modpack-v2.zip")).hasContent("old");
    }

    @Test
    void move_rejectsMovingFolderIntoItself() {
        assertStatus(() -> adminFileService.move("/mods", "/mods/client/mods"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void move_rejectsTakenTarget() {
        assertStatus(() -> adminFileService.move("/mods/modpack.zip", "/mods/client"), HttpStatus.CONFLICT);
    }

    @Test
    void moveToTrash_rejectsRoot() {
        assertStatus(() -> adminFileService.moveToTrash("/"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void moveToTrash_thenRestore_returnsFileToItsPlace() throws IOException {
        adminFileService.moveToTrash("/mods/modpack.zip");

        assertThat(dataDir.resolve("mods/modpack.zip")).doesNotExist();
        List<TrashEntry> trash = adminFileService.listTrash();
        assertThat(trash).singleElement().satisfies(entry -> {
            assertThat(entry.name()).isEqualTo("modpack.zip");
            assertThat(entry.originalPath()).isEqualTo("mods/modpack.zip");
            assertThat(entry.type()).isEqualTo(FileType.REGULAR_FILE);
            assertThat(entry.size()).isEqualTo(3);
        });

        adminFileService.restore(trash.getFirst().id());

        assertThat(dataDir.resolve("mods/modpack.zip")).hasContent("old");
        assertThat(adminFileService.listTrash()).isEmpty();
    }

    @Test
    void restore_recreatesMissingParentFolders() throws IOException {
        adminFileService.moveToTrash("/mods/modpack.zip");
        String id = adminFileService.listTrash().getFirst().id();
        adminFileService.moveToTrash("/mods");

        adminFileService.restore(id);

        assertThat(dataDir.resolve("mods/modpack.zip")).hasContent("old");
    }

    @Test
    void restore_rejectsTakenPlace() throws IOException {
        adminFileService.moveToTrash("/mods/modpack.zip");
        Files.writeString(dataDir.resolve("mods/modpack.zip"), "newer");
        String id = adminFileService.listTrash().getFirst().id();

        assertStatus(() -> adminFileService.restore(id), HttpStatus.CONFLICT);
        assertThat(dataDir.resolve("mods/modpack.zip")).hasContent("newer");
    }

    @Test
    void restore_rejectsUnknownOrMaliciousId() {
        assertStatus(() -> adminFileService.restore("../../mods"), HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteFromTrash_removesEntryPermanently() throws IOException {
        adminFileService.moveToTrash("/mods");
        String id = adminFileService.listTrash().getFirst().id();

        adminFileService.deleteFromTrash(id);

        assertThat(adminFileService.listTrash()).isEmpty();
        assertThat(dataDir.resolve(".trash").resolve(id)).doesNotExist();
    }

    @Test
    void deleteFromTrash_doesNotFollowSymlinks() throws IOException {
        Files.writeString(outsideDir.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(dataDir.resolve("mods/link"), outsideDir);
        adminFileService.moveToTrash("/mods");

        adminFileService.emptyTrash();

        assertThat(outsideDir.resolve("keep.txt")).hasContent("keep");
    }

    @Test
    void setPrivate_togglesMarker() throws IOException {
        adminFileService.setPrivate("/mods", true);
        assertThat(dataDir.resolve("mods/.private")).exists();

        adminFileService.setPrivate("/mods", false);
        assertThat(dataDir.resolve("mods/.private")).doesNotExist();
    }

    @Test
    void setPrivate_rejectsFiles() {
        assertStatus(() -> adminFileService.setPrivate("/mods/modpack.zip", true), HttpStatus.BAD_REQUEST);
    }

    private static InputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private List<Path> tempFiles() {
        try (Stream<Path> files = Files.walk(dataDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(FileService.TEMP_PREFIX)).toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertStatus(ThrowingAction action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
