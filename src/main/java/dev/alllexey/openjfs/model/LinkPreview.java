package dev.alllexey.openjfs.model;

import java.util.Optional;

// data for Open Graph tags, shown by messengers when a link to the web UI is shared
public record LinkPreview(String title, String pageTitle, String description, Optional<String> imagePath) {
}
