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
}
