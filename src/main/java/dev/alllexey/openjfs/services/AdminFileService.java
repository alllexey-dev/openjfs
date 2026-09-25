package dev.alllexey.openjfs.services;

import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.model.FileType;
import dev.alllexey.openjfs.model.TrashEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

// file management for the admin, every operation is logged
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminFileService {

    private static final int MAX_NAME_BYTES = 255;

    private static final Pattern TRASH_ID = Pattern.compile("[0-9]+-[0-9a-f]{8}");

    private static final String TRASH_INFO_FILE = "info.properties";

    private static final String TRASH_DATA_DIR = "data";

    private final MainConfigurationProperties properties;

    private final FileService fileService;

    private final Clock clock;

    // ---------- upload ----------

    // content is written to a temporary file first, so an interrupted upload never leaves a broken file
    public void upload(String path, InputStream content, long contentLength, boolean overwrite) throws IOException {
        Path target = resolveNewTarget(path);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw conflict("A folder with this name already exists: " + path);
        }
        if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw conflict("File already exists: " + path);
        }
        Path parent = target.getParent();
        if (contentLength > 0 && Files.getFileStore(parent).getUsableSpace() < contentLength) {
            throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "Not enough disk space for " + path);
        }

        Path temp = parent.resolve(FileService.TEMP_PREFIX + UUID.randomUUID() + ".part");
        try {
            long written = Files.copy(content, temp);
            if (contentLength >= 0 && written != contentLength) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Upload was interrupted: received %d of %d bytes".formatted(written, contentLength));
            }
            if (overwrite) {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, target);
            }
            log.info("Admin uploaded {} ({} bytes)", path, written);
        } catch (FileAlreadyExistsException e) {
            throw conflict("File already exists: " + path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ---------- folders, moving, privacy ----------

    public void createFolder(String path) throws IOException {
        Path target = resolveNewTarget(path);
        try {
            Files.createDirectory(target);
        } catch (FileAlreadyExistsException e) {
            throw conflict("Already exists: " + path);
        }
        log.info("Admin created folder {}", path);
    }

    // also used for renaming: the target is the full new path
    public void move(String from, String to) throws IOException {
        Path source = resolveExisting(from);
        Path target = resolveNewTarget(to);
        if (target.startsWith(source)) {
            throw badRequest("Cannot move a folder into itself: " + from);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw conflict("Already exists: " + to);
        }
        Files.move(source, target);
        log.info("Admin moved {} to {}", from, to);
    }

    public void setPrivate(String path, boolean makePrivate) throws IOException {
        Path dir = resolveExisting(path, true);
        if (!Files.isDirectory(dir)) {
            throw badRequest("Only folders can be private: " + path);
        }
        Path marker = dir.resolve(FileService.PRIVATE_MARKER);
        if (makePrivate && !Files.exists(marker)) {
            Files.createFile(marker);
        } else if (!makePrivate) {
            Files.deleteIfExists(marker);
        }
        log.info("Admin made {} {}", path.isEmpty() ? "/" : path, makePrivate ? "private" : "public");
    }

    // ---------- trash ----------

    public void moveToTrash(String path) throws IOException {
        Path source = resolveExisting(path);
        String id = clock.millis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path entry = trashDir().resolve(id);
        Files.createDirectories(entry.resolve(TRASH_DATA_DIR));

        Properties info = new Properties();
        info.setProperty("path", relativePath(source));
        info.setProperty("deletedAt", String.valueOf(clock.millis()));
        try (Writer writer = Files.newBufferedWriter(entry.resolve(TRASH_INFO_FILE), StandardCharsets.UTF_8)) {
            info.store(writer, null);
        }

        Files.move(source, entry.resolve(TRASH_DATA_DIR).resolve(source.getFileName()));
        log.info("Admin moved {} to trash ({})", path, id);
    }

    public List<TrashEntry> listTrash() throws IOException {
        Path trash = trashDir();
        if (!Files.isDirectory(trash)) return List.of();

        List<TrashEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(trash)) {
            for (Path entry : stream) {
                if (TRASH_ID.matcher(entry.getFileName().toString()).matches()) {
                    entries.add(readTrashEntry(entry));
                }
            }
        }
        entries.sort(Comparator.comparingLong(TrashEntry::deletedAtMillis).reversed());
        return entries;
    }

    public void restore(String id) throws IOException {
        Path entry = trashEntryDir(id);
        Properties info = readTrashInfo(entry);
        String originalPath = info.getProperty("path");
        if (originalPath == null) {
            throw badRequest("Trash entry has no original path: " + id);
        }
        Path target = fileService.getFullPath(Path.of(originalPath));
        if (!fileService.isInsideDataDir(target) || target.equals(properties.getDataPathAsPath())) {
            throw badRequest("Invalid original path in trash entry " + id);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw conflict("Cannot restore, the original place is taken: " + originalPath);
        }

        Files.createDirectories(target.getParent());
        Files.move(trashedItem(entry), target);
        deleteRecursively(entry);
        log.info("Admin restored {} from trash", originalPath);
    }

    public void deleteFromTrash(String id) throws IOException {
        deleteRecursively(trashEntryDir(id));
        log.info("Admin permanently deleted trash entry {}", id);
    }

    public void emptyTrash() throws IOException {
        for (TrashEntry entry : listTrash()) {
            deleteRecursively(trashDir().resolve(entry.id()));
        }
        log.info("Admin emptied trash");
    }

    private TrashEntry readTrashEntry(Path entry) throws IOException {
        Properties info = readTrashInfo(entry);
        Path item = trashedItem(entry);
        boolean isDirectory = Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS);
        return new TrashEntry(
                entry.getFileName().toString(),
                item.getFileName().toString(),
                info.getProperty("path"),
                isDirectory ? FileType.DIRECTORY : FileType.REGULAR_FILE,
                isDirectory ? -1 : Files.size(item),
                Long.parseLong(info.getProperty("deletedAt", "0")));
    }

    private Properties readTrashInfo(Path entry) throws IOException {
        Properties info = new Properties();
        try (Reader reader = Files.newBufferedReader(entry.resolve(TRASH_INFO_FILE), StandardCharsets.UTF_8)) {
            info.load(reader);
        }
        return info;
    }

    private Path trashedItem(Path entry) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(entry.resolve(TRASH_DATA_DIR))) {
            for (Path item : stream) return item;
        }
        throw new IOException("Trash entry is empty: " + entry);
    }

    private Path trashEntryDir(String id) {
        Path entry = trashDir().resolve(id);
        if (!TRASH_ID.matcher(id).matches() || !Files.isDirectory(entry)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such trash entry: " + id);
        }
        return entry;
    }

    private Path trashDir() {
        return properties.getDataPathAsPath().resolve(FileService.TRASH_DIR);
    }

    // never follows symlinks: only the links themselves are deleted, not their targets
    private static void deleteRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ---------- path checks ----------

    private Path resolveExisting(String path) {
        return resolveExisting(path, false);
    }

    private Path resolveExisting(String path, boolean allowRoot) {
        Path fullPath = fileService.resolveRequestedPath(path);
        if (!allowRoot && fullPath.equals(properties.getDataPathAsPath())) {
            throw badRequest("The root folder cannot be changed");
        }
        if (!fileService.checkAccess(fullPath).is2xxSuccessful()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found: " + path);
        }
        return fullPath;
    }

    // a path for a new file or folder: valid name inside an existing accessible folder
    private Path resolveNewTarget(String path) {
        Path target = fileService.resolveRequestedPath(path);
        if (!fileService.isInsideDataDir(target) || target.equals(properties.getDataPathAsPath())) {
            throw badRequest("Invalid path: " + path);
        }
        validateName(target.getFileName());

        Path parent = target.getParent();
        if (!fileService.checkAccess(parent).is2xxSuccessful() || !Files.isDirectory(parent)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found: " + relativePath(parent));
        }
        return target;
    }

    private void validateName(Path name) {
        String value = name.toString();
        if (value.isBlank() || value.equals(".") || value.equals("..") || value.contains("\\")) {
            throw badRequest("Invalid name: " + value);
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw badRequest("Name is too long: " + value);
        }
        if (fileService.isReservedName(name)) {
            throw badRequest("This name is reserved: " + value);
        }
        if (fileService.isHiddenByName(name) && !properties.isAllowHidden()) {
            throw badRequest("Names starting with a dot are hidden and not allowed: " + value);
        }
    }

    private String relativePath(Path fullPath) {
        return properties.getDataPathAsPath().relativize(fullPath).toString().replace('\\', '/');
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
