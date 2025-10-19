package dev.alllexey.openjfs.services;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.model.DirectoryInfo;
import dev.alllexey.openjfs.model.FileInfo;
import dev.alllexey.openjfs.model.RegularFileInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class FileService {

    private final MainConfigurationProperties properties;

    public Path resolveRequestedPath(String requestedPath) {
        if (requestedPath.startsWith("/")) {
            requestedPath = requestedPath.substring(1);
        }
        return getFullPath(Path.of(requestedPath));
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
        return HttpStatus.OK;
    }

    public boolean isInsideDataDir(Path fullPath) {
        return fullPath.startsWith(properties.getDataPathAsPath());
    }

    // check if file is hidden or in hidden directory
    public boolean isHidden(Path fullPath) {
        return isHiddenRelative(fullPath, properties.getDataPathAsPath());
    }

    private boolean isHiddenRelative(Path fullPath, Path basePath) {
        Path relPath = basePath.relativize(fullPath);
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

    // assume file (directory) exists and is accessible (visible, not outside, etc.)
    public FileInfo getFileInfo(Path fullPath, int depth) {
        if (depth < 0) return null; // should not happen
        Path relativize = properties.getDataPathAsPath().relativize(fullPath);
        Path parentPath = relativize.getParent();
        String relPath = parentPath == null ? "" : (parentPath + "/");
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
            boolean isEmpty = true;
            if (depth >= 1) {
                try (Stream<Path> stream = Files.list(fullPath)) {
                    stream.forEach(path -> {
                        if (isHiddenByName(path) && !properties.isAllowHidden())
                            return; // check by name because already parent dirs are checked
                        FileInfo fi = getFileInfo(path, depth - 1);
                        if (fi == null) return;
                        files.add(fi);
                    });
                } catch (IOException ignored) {
                }

                isEmpty = files.isEmpty();
            } else {
                if (properties.isAllowHidden()) {
                    try {
                        isEmpty = isEmpty(fullPath);
                    } catch (IOException ignored) {
                    }
                } else {
                    try {
                        isEmpty = !containsNonHidden(fullPath);
                    } catch (IOException ignored) {
                    }
                }
            }

            return DirectoryInfo.builder()
                    .path(relPath)
                    .name(name)
                    .lastModified(lastModified)
                    .lastModifiedMillis(lastModifiedMillis)
                    .isEmpty(isEmpty)
                    .files(depth >= 1 ? files : null)
                    .build();
        } else {
            return null; // should not happen
        }
    }

    public boolean isEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                return entries.findFirst().isEmpty();
            }
        }

        return false;
    }

    public boolean containsNonHidden(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                return entries.anyMatch(entry -> !isHiddenByName(entry));
            }
        }

        return false;
    }

    // assume file (directory) exists and is accessible (visible, not outside, etc.)
    public List<FileInfo> simpleSearch(Path fullPath, String query) {
        final String queryLowerCase = query.toLowerCase();
        try (Stream<Path> stream = Files.walk(fullPath)) {
            List<FileInfo> files = new ArrayList<>();
            stream.forEach(path -> {
                if (isHiddenByName(path) && !properties.isAllowHidden()) return;

                if (path.getFileName().toString().toLowerCase().contains(queryLowerCase)) {
                    FileInfo fi = getFileInfo(path, 0);
                    if (fi == null) return;
                    files.add(fi);
                }
            });
            return files;
        } catch (IOException e) {
            return null;
        }
    }

    public StreamingResponseBody zipDirectory(Path dirPath) {
        return outputStream -> {
            try (
                    ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(outputStream));
                    Stream<Path> walk = Files.walk(dirPath)
            ) {
                zipOut.setLevel(properties.getZipCompressionLevel());

                // skip if hidden or in hidden dir (and hidden files are disabled)
                Stream<Path> filteredWalk = properties.isAllowHidden()
                        ? walk
                        : walk.filter(path -> isHiddenRelative(path, dirPath));

                filteredWalk.forEach(path -> {
                    try {
                        Path relPath = dirPath.relativize(path);

                        if (Files.isDirectory(path)) {
                            if (!relPath.toString().isEmpty()) {
                                ZipEntry dirEntry = new ZipEntry(relPath + "/");
                                zipOut.putNextEntry(dirEntry);
                                zipOut.closeEntry();
                            }
                        } else {
                            ZipEntry fileEntry = new ZipEntry(relPath.toString());
                            zipOut.putNextEntry(fileEntry);
                            try (InputStream in = Files.newInputStream(path)) {
                                in.transferTo(zipOut);
                            }
                            zipOut.closeEntry();
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        };
    }
}
