package dev.alllexey.openjfs.controllers;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.alllexey.openjfs.model.TrashEntry;
import dev.alllexey.openjfs.services.AdminFileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

// available only to the logged in admin, see SecurityConfiguration
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminFileService adminFileService;

    // the body is the raw file content, streamed straight to disk
    @PutMapping("/files/{*path}")
    public ResponseEntity<Void> upload(@PathVariable String path,
                                       @RequestParam(defaultValue = "false") boolean overwrite,
                                       HttpServletRequest request) throws IOException {
        adminFileService.upload(path, request.getInputStream(), request.getContentLengthLong(), overwrite);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/files/{*path}")
    public ResponseEntity<Void> moveToTrash(@PathVariable String path) throws IOException {
        adminFileService.moveToTrash(path);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/folders")
    public ResponseEntity<Void> createFolder(@RequestBody PathRequest request) throws IOException {
        adminFileService.createFolder(request.path());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/move")
    public ResponseEntity<Void> move(@RequestBody MoveRequest request) throws IOException {
        adminFileService.move(request.from(), request.to());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/privacy")
    public ResponseEntity<Void> setPrivate(@RequestBody PrivacyRequest request) throws IOException {
        adminFileService.setPrivate(request.path(), request.makePrivate());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trash")
    public List<TrashEntry> listTrash() throws IOException {
        return adminFileService.listTrash();
    }

    @PostMapping("/trash/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable String id) throws IOException {
        adminFileService.restore(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/trash/{id}")
    public ResponseEntity<Void> deleteFromTrash(@PathVariable String id) throws IOException {
        adminFileService.deleteFromTrash(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/trash")
    public ResponseEntity<Void> emptyTrash() throws IOException {
        adminFileService.emptyTrash();
        return ResponseEntity.noContent().build();
    }

    public record PathRequest(String path) {
    }

    public record MoveRequest(String from, String to) {
    }

    public record PrivacyRequest(String path, @JsonProperty("private") boolean makePrivate) {
    }
}
