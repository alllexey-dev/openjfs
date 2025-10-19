package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.services.FileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/direct")
@RequiredArgsConstructor
public class DirectController {

    private final MainConfigurationProperties properties;

    private final FileService fileService;

    @GetMapping("/{*path}")
    public ResponseEntity<StreamingResponseBody> directDownload(@PathVariable String path) throws IOException {
        Path fullPath = fileService.resolveRequestedPath(path);

        HttpStatusCode accessCheck = fileService.checkAccess(fullPath);
        if (!accessCheck.is2xxSuccessful()) return ResponseEntity.status(accessCheck).build();

        String filename = fullPath.getFileName().toString();
        if (fullPath.equals(properties.getDataPathAsPath())) filename = "data";

        if (Files.isDirectory(fullPath)) {
            // 400 if it's a directory (and zipping dirs is disabled)
            if (!properties.isAllowZipDirectories()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }

            StreamingResponseBody zipStream = fileService.zipDirectory(fullPath);

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"%s.zip\"".formatted(filename))
                    .contentType(new MediaType("application", "zip"))
                    .body(zipStream);
        }

        if (Files.isRegularFile(fullPath)) {
            StreamingResponseBody stream = outputStream -> {
                try (InputStream inputStream = Files.newInputStream(fullPath)) {
                    inputStream.transferTo(outputStream);
                }
            };

            return ResponseEntity.ok()
                    .contentLength(Files.size(fullPath))
                    .header("Content-Disposition", "attachment; filename=\"%s\"".formatted(filename))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(stream);
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build(); // should not happen
    }
}
