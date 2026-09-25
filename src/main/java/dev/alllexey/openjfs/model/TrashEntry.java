package dev.alllexey.openjfs.model;

public record TrashEntry(String id, String name, String originalPath, FileType type, long size,
                         long deletedAtMillis) {
}
