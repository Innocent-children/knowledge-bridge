package com.openclaw.kbbridge;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ServiceAuthFilterTest {
    @Test void acceptsTheExistingPluginSignatureAndPreservesTheBody() throws Exception {
        var p=mock(BridgeProperties.class);when(p.sharedSecret()).thenReturn("synthetic-plugin-secret");
        String body="{\"requestId\":\"synthetic-request\",\"question\":\"文档\"}";
        String timestamp=Long.toString(System.currentTimeMillis());
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(p.sharedSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=Base64.getEncoder().encodeToString(mac.doFinal(("synthetic-request"+timestamp+Json.sha(body)).getBytes(StandardCharsets.UTF_8)));
        var req=new MockHttpServletRequest("POST","/api/v1/query");req.setContent(body.getBytes(StandardCharsets.UTF_8));req.addHeader("X-KB-RequestId","synthetic-request");req.addHeader("X-KB-Timestamp",timestamp);req.addHeader("X-KB-Signature",signature);
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();new ServiceAuthFilter(p).doFilter(req,response,chain);
        assertNotNull(chain.getRequest());assertEquals(body,new String(chain.getRequest().getInputStream().readAllBytes(),StandardCharsets.UTF_8));
    }
    @Test void rejectsUnsignedQueriesAndIncorrectInternalTokens() throws Exception {
        var p=mock(BridgeProperties.class);when(p.sharedSecret()).thenReturn("synthetic-plugin-secret");when(p.serviceToken()).thenReturn("synthetic-service-secret");
        for(String path:new String[]{"/api/v1/query","/internal/rewrite"}) {
            var response=new MockHttpServletResponse();var chain=new MockFilterChain();new ServiceAuthFilter(p).doFilter(new MockHttpServletRequest("POST",path),response,chain);
            assertEquals(401,response.getStatus());assertNull(chain.getRequest());
        }
    }
}
