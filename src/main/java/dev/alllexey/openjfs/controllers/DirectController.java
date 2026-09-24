package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.services.FileService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/direct")
@RequiredArgsConstructor
public class DirectController {

    private final MainConfigurationProperties properties;

    private final FileService fileService;

    @GetMapping("/{*path}")
    public ResponseEntity<Resource> directDownload(@PathVariable String path, HttpServletResponse response) throws IOException {
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

            // archive size is unknown in advance, so it is written straight into the response
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment(filename + ".zip"));
            fileService.zipDirectory(fullPath, response.getOutputStream());
            return null; // response is already written
        }

        if (Files.isRegularFile(fullPath)) {
            // Resource body gives Range (resume, seeking) and conditional requests support
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
                    .lastModified(Files.getLastModifiedTime(fullPath).toMillis())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new FileSystemResource(fullPath));
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); // special files (sockets, pipes, devices)
    }

    private static String attachment(String filename) {
        return ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }
}
