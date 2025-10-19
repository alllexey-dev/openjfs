package dev.alllexey.openjfs.model;

import java.time.LocalDateTime;

public interface FileInfo {

    String getPath();

    String getName();

    FileType getType();

    LocalDateTime getLastModified();

    long getLastModifiedMillis();
}
