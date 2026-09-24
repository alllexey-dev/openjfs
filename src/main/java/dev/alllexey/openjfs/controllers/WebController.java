package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import dev.alllexey.openjfs.model.LinkPreview;
import dev.alllexey.openjfs.services.LinkPreviewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
@RequestMapping("/ui")
@RequiredArgsConstructor
public class WebController {

    private final MainConfigurationProperties properties;

    private final LinkPreviewService linkPreviewService;

    @GetMapping("/{*path}")
    public String web(@PathVariable String path, Model model) {
        model.addAttribute("allowDownloadDirs", properties.isAllowZipDirectories());
        model.addAttribute("serverName", properties.getServerName());
        model.addAttribute("searchMaxResults", properties.getSearchMaxResults());

        LinkPreview preview = linkPreviewService.preview(path);
        model.addAttribute("preview", preview);
        // messengers require absolute urls
        model.addAttribute("previewUrl", ServletUriComponentsBuilder.fromCurrentRequest().toUriString());
        model.addAttribute("previewImageUrl", preview.imagePath().map(WebController::absoluteRawUrl).orElse(null));
        return "index";
    }

    private static String absoluteRawUrl(String path) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .pathSegment("raw")
                .pathSegment(path.split("/"))
                .build()
                .encode()
                .toUriString();
    }
}
