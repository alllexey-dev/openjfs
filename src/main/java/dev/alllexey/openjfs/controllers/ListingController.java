package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.model.FileInfo;
import dev.alllexey.openjfs.services.FileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

@RestController
@RequestMapping("/list")
@RequiredArgsConstructor
public class ListingController {

    private final FileService fileService;

    @GetMapping("/{*path}")
    public ResponseEntity<FileInfo> list(@PathVariable String path) {
        Path fullPath = fileService.resolveRequestedPath(path);
        HttpStatusCode accessCheck = fileService.checkAccess(fullPath);
        if (!accessCheck.is2xxSuccessful()) return ResponseEntity.status(accessCheck).build();

        FileInfo fi = fileService.getFileInfo(fullPath, 1);

        return ResponseEntity.status(HttpStatus.OK).body(fi);
    }
}
