package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/text")
@RequiredArgsConstructor
public class TextController {

    private final FileService fileService;

    @GetMapping("/{*path}")
    public ResponseEntity<StreamingResponseBody> asText(@PathVariable String path) throws IOException {
        Path fullPath = fileService.resolveRequestedPath(path);

        HttpStatusCode accessCheck = fileService.checkAccess(fullPath);
        if (!accessCheck.is2xxSuccessful()) return ResponseEntity.status(accessCheck).build();

        if (Files.isDirectory(fullPath)) {
            return ResponseEntity.badRequest().build(); // can't do this for directories
        }

        if (Files.isRegularFile(fullPath)) {
            StreamingResponseBody stream = outputStream -> {
                try (InputStream inputStream = Files.newInputStream(fullPath)) {
                    inputStream.transferTo(outputStream);
                }
            };

            return ResponseEntity.ok()
                    .contentLength(Files.size(fullPath))
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(stream);
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build(); // should not happen
    }
}
