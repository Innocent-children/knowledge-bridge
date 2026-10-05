package com.openclaw.kbbridge;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class RewriteQualityTest {
    private final RewriteQuality quality=new RewriteQuality();
    @Test void acceptsNormalMarkdownWithoutAForcedQuestionQuota() {
        assertDoesNotThrow(()->quality.validate("## 一条有用问题\n\n回答保留原文条件。\n\n```java\nSystem.out.println(\"```\");\n```",Set.of()));
        assertDoesNotThrow(()->quality.validate("~~~text\n内容\n~~~",Set.of()));
    }
    @Test void rejectsMissingTruncatedAndInvalidContent() {
        for(String content:new String[]{"","```java\nreturn 1;","内容\uFFFD","![图](https://external.invalid/a.png)"})assertThrows(ProcessingFailure.class,()->quality.validate(content,Set.of()));
    }
    @Test void allowsOnlyOriginalAttachmentReferences() {
        String id="11111111-1111-4111-8111-111111111111";
        assertDoesNotThrow(()->quality.validate("![图](attachment://"+id+")",Set.of(id)));
        assertThrows(ProcessingFailure.class,()->quality.validate("![图](attachment://"+id+")",Set.of()));
    }
    @Test void acceptsIndependentChunksWithCompleteCodeAndAttachmentReferences() {
        String id="11111111-1111-4111-8111-111111111111";
        String content="## 启动服务\r\n\r\n```bash\r\n./deploy.sh\r\n```\r\n\r\n---CHUNK---\r\n\r\n## 部署截图\r\n![图](attachment://"+id+")";
        assertDoesNotThrow(()->quality.validate(content,Set.of(id)));
    }
    @Test void rejectsBoundariesThatWouldSplitCodeOrProduceEmptyChunks() {
        for(String content:new String[]{
            "---CHUNK---\n正文","正文\n---CHUNK---","正文\n---CHUNK---\n\n---CHUNK---\n第二块",
            "正文---CHUNK---第二块","`---CHUNK---`","```text\n第一行\n---CHUNK---\n第二行\n```",
            "~~~text\n---CHUNK---\n~~~","正文\n    ---CHUNK---\n第二块"
        }) {
            var failure=assertThrows(ProcessingFailure.class,()->quality.validate(content,Set.of()),content);
            assertEquals("INVALID_REWRITE_CHUNKS",failure.code());assertFalse(failure.retryable());
        }
    }
}
