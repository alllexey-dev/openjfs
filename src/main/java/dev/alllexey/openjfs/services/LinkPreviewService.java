package dev.alllexey.openjfs.services;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.model.DirectoryInfo;
import dev.alllexey.openjfs.model.FileInfo;
import dev.alllexey.openjfs.model.FileType;
import dev.alllexey.openjfs.model.LinkPreview;
import dev.alllexey.openjfs.model.RegularFileInfo;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LinkPreviewService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private static final List<String> SIZE_UNITS = List.of("KB", "MB", "GB", "TB", "PB");

    // formats messengers can show as a preview image
    private static final Set<String> PREVIEW_IMAGE_SUBTYPES = Set.of("png", "jpeg", "gif", "webp");

    private final MainConfigurationProperties properties;

    private final FileService fileService;

    public LinkPreview preview(String requestedPath) {
        try {
            Path fullPath = fileService.resolveRequestedPath(requestedPath);
            if (!fileService.checkAccess(fullPath).is2xxSuccessful()) return defaultPreview();

            FileInfo info = fileService.getFileInfo(fullPath, 1);
            if (info instanceof RegularFileInfo file) return filePreview(file);
            if (info instanceof DirectoryInfo directory) return directoryPreview(directory);
            return defaultPreview();
        } catch (ResponseStatusException | UncheckedIOException e) {
            return defaultPreview(); // invalid path or unreadable directory, the page itself still works
        }
    }

    private LinkPreview defaultPreview() {
        return new LinkPreview(properties.getServerName(), properties.getServerName(), "File server", Optional.empty());
    }

    private LinkPreview filePreview(RegularFileInfo file) {
        String description = file.getLastModified() == null
                ? formatSize(file.getSize())
                : formatSize(file.getSize()) + " · " + DATE_FORMAT.format(file.getLastModified());
        Optional<String> imagePath = isPreviewImage(file.getName())
                ? Optional.of(file.getPath() + file.getName())
                : Optional.empty();
        return new LinkPreview(file.getName(), pageTitle(file.getName()), description, imagePath);
    }

    private LinkPreview directoryPreview(DirectoryInfo directory) {
        String description = "Folder · " + describeContents(directory.getFiles());
        if (directory.getName().isEmpty()) {
            return new LinkPreview(properties.getServerName(), properties.getServerName(), description, Optional.empty());
        }
        return new LinkPreview(directory.getName(), pageTitle(directory.getName()), description, Optional.empty());
    }

    private String pageTitle(String name) {
        return name + " · " + properties.getServerName();
    }

    private static String describeContents(List<FileInfo> files) {
        if (files.isEmpty()) return "empty";
        long folders = files.stream().filter(f -> f.getType() == FileType.DIRECTORY).count();
        long regularFiles = files.size() - folders;
        if (folders == 0) return plural(regularFiles, "file");
        if (regularFiles == 0) return plural(folders, "folder");
        return plural(folders, "folder") + ", " + plural(regularFiles, "file");
    }

    private static String plural(long count, String word) {
        return count + " " + word + (count == 1 ? "" : "s");
    }

    private static boolean isPreviewImage(String filename) {
        return MediaTypeFactory.getMediaType(filename)
                .map(type -> "image".equals(type.getType()) && PREVIEW_IMAGE_SUBTYPES.contains(type.getSubtype()))
                .orElse(false);
    }

    // same format as in the web UI
    private static String formatSize(long bytes) {
        if (bytes < 0) return "unknown size";
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < SIZE_UNITS.size() - 1) {
            value /= 1024;
            unit++;
        }
        String number = value < 10 ? String.format(Locale.ROOT, "%.1f", value) : String.valueOf(Math.round(value));
        return number + " " + SIZE_UNITS.get(unit);
    }
}
