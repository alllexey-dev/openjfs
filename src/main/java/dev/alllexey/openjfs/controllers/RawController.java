package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.services.FileService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// serves files inline with their real content type, used by the web UI to preview media
@RestController
@RequestMapping("/raw")
@RequiredArgsConstructor
public class RawController {

    private final FileService fileService;

    @GetMapping("/{*path}")
    public ResponseEntity<Resource> raw(@PathVariable String path) throws IOException {
        Path fullPath = fileService.resolveRequestedPath(path);

        HttpStatusCode accessCheck = fileService.checkAccess(fullPath);
        if (!accessCheck.is2xxSuccessful()) return ResponseEntity.status(accessCheck).build();

        if (Files.isDirectory(fullPath)) {
            return ResponseEntity.badRequest().build(); // can't do this for directories
        }

        if (!Files.isRegularFile(fullPath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); // special files (sockets, pipes, devices)
        }

        String filename = fullPath.getFileName().toString();
        MediaType mediaType = MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .lastModified(Files.getLastModifiedTime(fullPath).toMillis())
                .contentType(mediaType);

        // served files are untrusted: html/svg opened directly must not run scripts on our origin;
        // pdf is excluded because browsers refuse to render sandboxed pdf documents
        if (!MediaType.APPLICATION_PDF.equalsTypeAndSubtype(mediaType)) {
            response.header("Content-Security-Policy", "sandbox");
        }

        return response.body(new FileSystemResource(fullPath));
    }
}
