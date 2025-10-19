package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.model.FileInfo;
import dev.alllexey.openjfs.services.FileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final FileService fileService;

    @GetMapping("/{*path}")
    public ResponseEntity<List<FileInfo>> search(@PathVariable String path, @RequestParam("q") String query) {
        Path fullPath = fileService.resolveRequestedPath(path);

        HttpStatusCode accessCheck = fileService.checkAccess(fullPath);
        if (!accessCheck.is2xxSuccessful()) return ResponseEntity.status(accessCheck).build();

        List<FileInfo> result = fileService.simpleSearch(fullPath, query);

        return ResponseEntity.status(HttpStatus.OK).body(result);
    }
}
