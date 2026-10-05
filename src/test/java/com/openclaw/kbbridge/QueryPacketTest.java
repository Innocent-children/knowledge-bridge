package com.openclaw.kbbridge;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class QueryPacketTest {
    private QueryPacket packet(int total) {
        var properties=mock(BridgeProperties.class);when(properties.searchMaxSources()).thenReturn(5);when(properties.sourceMaxChars()).thenReturn(4);when(properties.contextMaxChars()).thenReturn(total);return new QueryPacket(properties);
    }
    @Test void kbDirectiveAndFlagBothForbidModelSupplementEvenWithoutHits() {
        var p=packet(10);assertTrue(p.strict("#KB 如何保存",Map.of()));assertEquals("如何保存",p.question("#kb 如何保存"));
        var result=p.build("request",true,Map.of("items",List.of()));assertEquals("KB_ONLY",result.get("route"));assertEquals(false,result.get("allowModelSupplement"));
    }
    @Test void totalBudgetAndUnicodeArePreserved() {
        var item=Map.<String,Object>of("title","标题","content","甲乙丙丁😀","documentId","doc","publicationId","release","source","BLOG","score",0.8);
        var result=packet(6).build("request",false,Map.of("items",List.of(item,item)));
        @SuppressWarnings("unchecked") var sources=(List<Map<String,Object>>)result.get("sources");
        assertEquals("甲乙丙丁",sources.get(0).get("content"));assertEquals("甲乙",sources.get(1).get("content"));
        assertEquals(true,((Map<?,?>)result.get("retrievalQuality")).get("truncated"));
    }
}
