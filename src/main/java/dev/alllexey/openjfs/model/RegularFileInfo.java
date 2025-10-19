package dev.alllexey.openjfs.model;

import lombok.*;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RegularFileInfo implements FileInfo {

    private String path;

    private String name;

    private LocalDateTime lastModified;

    private long lastModifiedMillis;

    @Override
    public FileType getType() {
        return FileType.REGULAR_FILE;
    }

    private long size;
}
