package com.openclaw.kbbridge.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMarkdownRewriteToolTest {

    @TempDir
    Path tempDir;

    @Test
    void outputPath_keepsDirectoryAndAddsKindSuffix() {
        Path output = Path.of("docs", "个人知识库_rewrite");
        Path relative = Path.of("mall", "架构篇", "AAA.md");

        assertEquals(
                Path.of("docs", "个人知识库_rewrite", "mall", "架构篇", "AAA_guide.md"),
                LocalMarkdownRewriteTool.outputPath(output, relative, "guide"));
        assertEquals(
                Path.of("docs", "个人知识库_rewrite", "mall", "架构篇", "AAA_qa.md"),
                LocalMarkdownRewriteTool.outputPath(output, relative, "qa"));
    }

    @Test
    void extractImageReferences_keepsExactMarkdownAndCountsDuplicates() {
        String content = """
                # 文档

                ![a](./img/a.png)
                ![a](./img/a.png)
                ![[img/b.png]]
                <img src="./img/c.png" alt="c">
                """;

        LocalMarkdownRewriteTool.ImageReferences references =
                LocalMarkdownRewriteTool.extractImageReferences(content);

        assertEquals(4, references.totalCount());
        assertTrue(references.references().containsKey("![a](./img/a.png)"));
        assertEquals(2, references.references().get("![a](./img/a.png)"));
        assertTrue(references.references().containsKey("![[img/b.png]]"));
        assertTrue(references.references().containsKey("<img src=\"./img/c.png\" alt=\"c\">"));
        assertTrue(references.missingFrom("![a](./img/a.png)\n![[img/b.png]]").contains("![a](./img/a.png)"));
    }

    @Test
    void extractImageReferences_matchesProjectImagePath() {
        String image = "![1723032625445-64fff028-e044-4f4f-97d5-1638ceec8185.png](./img/GrA4S08dilNkUoVR/1723032625445-64fff028-e044-4f4f-97d5-1638ceec8185-312747.png)";

        LocalMarkdownRewriteTool.ImageReferences references =
                LocalMarkdownRewriteTool.extractImageReferences(image);

        assertEquals(1, references.totalCount());
        assertTrue(references.references().containsKey(image));
    }

    @Test
    void ensureGuideChunkDelimiter_appendsDelimiterWhenGuideHasSingleChunk() {
        String content = "# 标题\n\n只有一个很短的 chunk。\n";

        String normalized = LocalMarkdownRewriteTool.ensureGuideChunkDelimiter(content);

        assertEquals("# 标题\n\n只有一个很短的 chunk。\n\n---CHUNK---\n", normalized);
    }

    @Test
    void ensureGuideChunkDelimiter_keepsExistingDelimiter() {
        String content = "# 标题\n\n第一段\n\n---CHUNK---\n\n## 第二段\n内容\n";

        String normalized = LocalMarkdownRewriteTool.ensureGuideChunkDelimiter(content);

        assertEquals(content, normalized);
    }

    @Test
    void copyImageDirectories_preservesRelativeImgTree() throws Exception {
        Path input = tempDir.resolve("docs").resolve("个人知识库");
        Path output = tempDir.resolve("docs").resolve("个人知识库_rewrite");
        Path sourceImage = input.resolve("mall").resolve("架构篇").resolve("img").resolve("x").resolve("a.png");
        Files.createDirectories(sourceImage.getParent());
        Files.writeString(sourceImage, "image", StandardCharsets.UTF_8);

        int copied = LocalMarkdownRewriteTool.copyImageDirectories(input, output);

        assertEquals(1, copied);
        assertTrue(Files.exists(output.resolve("mall").resolve("架构篇").resolve("img").resolve("x").resolve("a.png")));
    }

    @Test
    void findMarkdownFiles_excludesOutputDirectoryWhenNested() throws Exception {
        Path input = tempDir.resolve("docs");
        Path output = input.resolve("rewrite");
        Files.createDirectories(input.resolve("a"));
        Files.createDirectories(output);
        Path source = input.resolve("a").resolve("one.md");
        Path generated = output.resolve("two.md");
        Files.writeString(source, "# one", StandardCharsets.UTF_8);
        Files.writeString(generated, "# two", StandardCharsets.UTF_8);

        List<Path> files = LocalMarkdownRewriteTool.findMarkdownFiles(input, output, 0);

        assertEquals(List.of(source.toAbsolutePath().normalize()), files);
        assertFalse(files.contains(generated.toAbsolutePath().normalize()));
    }
}
