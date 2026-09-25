package dev.alllexey.openjfs.services;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.model.DirectoryInfo;
import dev.alllexey.openjfs.model.FileInfo;
import dev.alllexey.openjfs.model.RegularFileInfo;
import dev.alllexey.openjfs.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class FileService {

    // follow symlinks (loops are detected by walkFileTree), but never leave the data dir, see isAccessibleChild
    private static final Set<FileVisitOption> WALK_OPTIONS = EnumSet.of(FileVisitOption.FOLLOW_LINKS);

    // a folder containing this marker file is visible only to the admin
    public static final String PRIVATE_MARKER = ".private";

    public static final String TRASH_DIR = ".trash";

    // prefix of temporary files created during uploads
    public static final String TEMP_PREFIX = ".openjfs-";

    private static final Set<String> RESERVED_NAMES = Set.of(PRIVATE_MARKER, TRASH_DIR);

    private final MainConfigurationProperties properties;

    private final CurrentUser currentUser;

    public Path resolveRequestedPath(String requestedPath) {
        if (requestedPath.startsWith("/")) {
            requestedPath = requestedPath.substring(1);
        }
        try {
            return getFullPath(Path.of(requestedPath));
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path: " + requestedPath, e);
        }
    }

    public Path getFullPath(Path requestedPath) {
        return properties.getDataPathAsPath().resolve(requestedPath).normalize();
    }

    public HttpStatusCode checkAccess(Path fullPath) {
        // 403 if outside our data dir
        if (!isInsideDataDir(fullPath)) return HttpStatus.FORBIDDEN;
        // 404 if it does not exist
        if (!Files.exists(fullPath)) return HttpStatus.NOT_FOUND;
        // 404 if hidden or in hidden dir (and hidden files are disabled)
        if (isHidden(fullPath) && !properties.isAllowHidden()) return HttpStatus.NOT_FOUND;
        // 404 for service files (trash, private markers, unfinished uploads), even for the admin
        if (containsReservedSegment(fullPath)) return HttpStatus.NOT_FOUND;
        // 403 if it is (or is inside) a symlink pointing outside our data dir
        if (!isRealPathInsideDataDir(fullPath)) return HttpStatus.FORBIDDEN;
        // 404 if it is (or is inside) a private folder, existence of private files is not revealed
        if (!currentUser.isAdmin() && isInPrivateFolder(fullPath)) return HttpStatus.NOT_FOUND;
        return HttpStatus.OK;
    }

    public boolean isInsideDataDir(Path fullPath) {
        return fullPath.startsWith(properties.getDataPathAsPath());
    }

    public boolean isRealPathInsideDataDir(Path fullPath) {
        try {
            return fullPath.toRealPath().startsWith(properties.getDataPathAsPath().toRealPath());
        } catch (IOException e) {
            return false; // broken symlink or no permissions
        }
    }

    // check if file is hidden or in hidden directory
    public boolean isHidden(Path fullPath) {
        Path relPath = properties.getDataPathAsPath().relativize(fullPath);
        for (Path segment : relPath) {
            if (isHiddenByName(segment)) {
                return true;
            }
        }
        return false;
    }

    public boolean isHiddenByName(Path filePath) {
        return filePath.getFileName().toString().startsWith(".");
    }

    public boolean isReservedName(Path filePath) {
        String name = filePath.getFileName().toString();
        return RESERVED_NAMES.contains(name) || name.startsWith(TEMP_PREFIX);
    }

    private boolean containsReservedSegment(Path fullPath) {
        for (Path segment : properties.getDataPathAsPath().relativize(fullPath)) {
            if (!segment.toString().isEmpty() && isReservedName(segment)) return true;
        }
        return false;
    }

    public boolean isPrivateFolder(Path dir) {
        return Files.exists(dir.resolve(PRIVATE_MARKER));
    }

    // checks both the requested path and the real one, so a symlink can't expose a private folder
    public boolean isInPrivateFolder(Path fullPath) {
        Path root = properties.getDataPathAsPath();
        if (hasPrivateAncestor(fullPath, root)) return true;
        try {
            return hasPrivateAncestor(fullPath.toRealPath(), root.toRealPath());
        } catch (IOException e) {
            return true; // can't verify, so treat as private
        }
    }

    private boolean hasPrivateAncestor(Path path, Path root) {
        Path dir = Files.isDirectory(path) ? path : path.getParent();
        while (dir != null && dir.startsWith(root)) {
            if (isPrivateFolder(dir)) return true;
            dir = dir.getParent();
        }
        return false;
    }

    // check a direct child of an accessible directory (parent dirs are already checked)
    public boolean isAccessibleChild(Path path) {
        if (isReservedName(path)) return false;
        if (isHiddenByName(path) && !properties.isAllowHidden()) return false;
        if (!isRealPathInsideDataDir(path)) return false;
        return currentUser.isAdmin() || !isPrivateChild(path);
    }

    private boolean isPrivateChild(Path path) {
        if (Files.isDirectory(path) && isPrivateFolder(path)) return true;
        // a symlink may point into a private folder located elsewhere
        return Files.isSymbolicLink(path) && isInPrivateFolder(path);
    }

    // assume file (directory) exists and is accessible (visible, not outside, etc.)
    public FileInfo getFileInfo(Path fullPath, int depth) {
        if (depth < 0) return null; // should not happen
        Path relativize = properties.getDataPathAsPath().relativize(fullPath);
        Path parentPath = relativize.getParent();
        String relPath = parentPath == null ? "" : (toSlashSeparated(parentPath) + "/");
        String name = relativize.toString().isEmpty() ? "" : fullPath.getFileName().toString();
        long lastModifiedMillis = -1;
        LocalDateTime lastModified = null;

        try {
            Instant instant = Files.getLastModifiedTime(fullPath).toInstant();
            lastModifiedMillis = instant.toEpochMilli();
            lastModified = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (IOException ignored) {
        }

        if (Files.isRegularFile(fullPath)) {
            long size = -1;
            try {
                size = Files.size(fullPath);
            } catch (IOException ignored) {
            }

            return RegularFileInfo.builder()
                    .path(relPath)
                    .name(name)
                    .lastModified(lastModified)
                    .lastModifiedMillis(lastModifiedMillis)
                    .size(size)
                    .build();
        } else if (Files.isDirectory(fullPath)) {
            List<FileInfo> files = new ArrayList<>();
            boolean isEmpty;
            if (depth >= 1) {
                try (Stream<Path> stream = Files.list(fullPath)) {
                    stream.filter(this::isAccessibleChild).forEach(path -> {
                        FileInfo fi = getFileInfo(path, depth - 1);
                        if (fi == null) return;
                        files.add(fi);
                    });
                } catch (AccessDeniedException e) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No permissions to list " + fullPath, e);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to list directory " + fullPath, e);
                }

                isEmpty = files.isEmpty();
            } else {
                isEmpty = !hasAccessibleChildren(fullPath);
            }

            return DirectoryInfo.builder()
                    .path(relPath)
                    .name(name)
                    .lastModified(lastModified)
                    .lastModifiedMillis(lastModifiedMillis)
                    .isEmpty(isEmpty)
                    .isPrivate(isPrivateFolder(fullPath))
                    .files(depth >= 1 ? files : null)
                    .build();
        } else {
            return null; // special files (sockets, pipes, devices) are not served
        }
    }

    public boolean hasAccessibleChildren(Path path) {
        try (Stream<Path> entries = Files.list(path)) {
            return entries.anyMatch(this::isAccessibleChild);
        } catch (IOException e) {
            return false;
        }
    }

    // assume file (directory) exists and is accessible (visible, not outside, etc.)
    public List<FileInfo> simpleSearch(Path fullPath, String query) {
        final String queryLowerCase = query.toLowerCase();
        List<FileInfo> files = new ArrayList<>();

        try {
            walkAccessible(fullPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(fullPath)) return FileVisitResult.CONTINUE;
                    return addIfMatches(dir) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    return addIfMatches(file) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }

                // returns false when the result limit is reached
                private boolean addIfMatches(Path path) {
                    if (path.getFileName().toString().toLowerCase().contains(queryLowerCase)) {
                        FileInfo fi = getFileInfo(path, 0);
                        if (fi != null) files.add(fi);
                    }
                    return files.size() < properties.getSearchMaxResults();
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to search in " + fullPath, e);
        }

        return files;
    }

    public void zipDirectory(Path dirPath, OutputStream outputStream) throws IOException {
        try (ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(outputStream))) {
            zipOut.setLevel(properties.getZipCompressionLevel());

            walkAccessible(dirPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(dirPath)) {
                        zipOut.putNextEntry(new ZipEntry(toSlashSeparated(dirPath.relativize(dir)) + "/"));
                        zipOut.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // never read pipes or devices: reading a FIFO blocks forever
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;

                    InputStream in;
                    try {
                        in = Files.newInputStream(file);
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE; // skip unreadable files instead of breaking the archive
                    }

                    try (in) {
                        zipOut.putNextEntry(new ZipEntry(toSlashSeparated(dirPath.relativize(file))));
                        in.transferTo(zipOut);
                        zipOut.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    // walks the tree passing only accessible entries to the visitor;
    // hidden (if disabled), escaping and unreadable entries are skipped
    private void walkAccessible(Path start, SimpleFileVisitor<Path> visitor) throws IOException {
        Files.walkFileTree(start, WALK_OPTIONS, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(start) && !isAccessibleChild(dir)) return FileVisitResult.SKIP_SUBTREE;
                return visitor.preVisitDirectory(dir, attrs);
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!isAccessibleChild(file)) return FileVisitResult.CONTINUE;
                return visitor.visitFile(file, attrs);
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // no permissions, symlink loop, deleted during walk, etc.
            }
        });
    }

    private static String toSlashSeparated(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }
}
