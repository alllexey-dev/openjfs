package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.services.FileService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/text")
@RequiredArgsConstructor
public class TextController {

    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final FileService fileService;

    @GetMapping("/{*path}")
    public ResponseEntity<Resource> asText(@PathVariable String path) {
        Path fullPath = fileService.resolveRequestedPath(path);

        HttpStatusCode accessCheck = fileService.checkAccess(fullPath);
        if (!accessCheck.is2xxSuccessful()) return ResponseEntity.status(accessCheck).build();

        if (Files.isDirectory(fullPath)) {
            return ResponseEntity.badRequest().build(); // can't do this for directories
        }

        if (Files.isRegularFile(fullPath)) {
            return ResponseEntity.ok()
                    .contentType(TEXT_PLAIN_UTF8)
                    .body(new FileSystemResource(fullPath));
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); // special files (sockets, pipes, devices)
    }
}
