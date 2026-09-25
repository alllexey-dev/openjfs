package dev.alllexey.openjfs.configuration;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.Range;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "openjfs")
@Validated
@Getter
@Setter
public class MainConfigurationProperties {

    private String dataPath;

    private boolean allowHidden;

    private boolean allowZipDirectories;

    @Range(min = 0, max = 9)
    private int zipCompressionLevel;

    @Min(1)
    private int searchMaxResults;

    private String serverName;

    // admin mode (uploads, file management, private folders) is enabled only when the password is set
    private String adminPassword;

    public Path getDataPathAsPath() {
        return Path.of(dataPath).toAbsolutePath().normalize();
    }

    public boolean isAdminEnabled() {
        return adminPassword != null && !adminPassword.isBlank();
    }
}
