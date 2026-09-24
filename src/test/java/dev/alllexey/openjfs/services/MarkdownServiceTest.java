package dev.alllexey.openjfs.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownServiceTest {

    private final MarkdownService markdownService = new MarkdownService();

    @Test
    void toHtml_rendersMarkdown() {
        String html = markdownService.toHtml("# Установка\n\nСкачай **архив**.");

        assertThat(html).contains("<h1>Установка</h1>", "<strong>архив</strong>");
    }

    @Test
    void toHtml_rendersTables() {
        String html = markdownService.toHtml("| Mod | Version |\n|---|---|\n| jei | 29.6 |");

        assertThat(html).contains("<table>", "<td>jei</td>");
    }

    @Test
    void toHtml_escapesRawHtml() {
        String html = markdownService.toHtml("<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>");

        assertThat(html).doesNotContain("<script>", "<img").contains("&lt;script&gt;");
    }

    @Test
    void toHtml_dropsJavascriptLinks() {
        String html = markdownService.toHtml("[click](javascript:alert(1))");

        assertThat(html).doesNotContain("javascript:");
    }
}
