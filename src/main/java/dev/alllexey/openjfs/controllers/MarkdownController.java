package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.services.FileService;
import dev.alllexey.openjfs.services.MarkdownService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// renders markdown files (e.g. folder README) to an html fragment for the web UI
@RestController
@RequestMapping("/markdown")
@RequiredArgsConstructor
public class MarkdownController {

    private static final long MAX_MARKDOWN_SIZE = 512 * 1024;

    private static final MediaType TEXT_HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final FileService fileService;

    private final MarkdownService markdownService;

    @GetMapping("/{*path}")
    public ResponseEntity<String> render(@PathVariable String path) throws IOException {
        Path fullPath = fileService.resolveRequestedPath(path);

        HttpStatusCode accessCheck = fileService.checkAccess(fullPath);
        if (!accessCheck.is2xxSuccessful()) return ResponseEntity.status(accessCheck).build();

        if (!Files.isRegularFile(fullPath)) {
            return ResponseEntity.badRequest().build();
        }

        if (Files.size(fullPath) > MAX_MARKDOWN_SIZE) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }

        // unlike Files.readString, replaces malformed bytes instead of failing
        String markdown = new String(Files.readAllBytes(fullPath), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(TEXT_HTML_UTF8)
                .body(markdownService.toHtml(markdown));
    }
}
