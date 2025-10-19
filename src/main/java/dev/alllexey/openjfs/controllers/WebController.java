package dev.alllexey.openjfs.controllers;

import lombok.RequiredArgsConstructor;
import dev.alllexey.openjfs.configuration.MainConfigurationProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/ui")
@RequiredArgsConstructor
public class WebController {

    private final MainConfigurationProperties properties;

    @GetMapping(value = "**")
    public String web(Model model) {
        model.addAttribute("allowDownloadDirs", properties.isAllowZipDirectories());
        model.addAttribute("serverName", properties.getServerName());
        return "index";
    }

}
