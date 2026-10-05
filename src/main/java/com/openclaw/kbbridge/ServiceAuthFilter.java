package com.openclaw.kbbridge;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
@org.springframework.core.annotation.Order(10)
public class ServiceAuthFilter extends OncePerRequestFilter {
    private final BridgeProperties p;
    public ServiceAuthFilter(BridgeProperties p) {this.p=p;}
    private boolean equal(String a,String b) {return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI();HttpServletRequest request=req;
        try {
            if(path.startsWith("/internal/") && (p.serviceToken().isBlank() || !equal("Bearer "+p.serviceToken(),req.getHeader("Authorization"))))throw new IllegalArgumentException("Invalid service token");
            if(path.equals("/api/v1/ingest/file") && !equal(p.sharedSecret(),req.getHeader("X-KB-File-Token")))throw new IllegalArgumentException("Invalid file token");
            if(path.equals("/api/v1/query") || path.equals("/api/v1/ingest/candidate")) {
                byte[] bytes=req.getInputStream().readNBytes(2097153);if(bytes.length>2097152)throw new IllegalArgumentException("Request too large");
                String id=req.getHeader("X-KB-RequestId"),timestamp=req.getHeader("X-KB-Timestamp");
                long now=System.currentTimeMillis(),sent=Long.parseLong(timestamp);
                if(sent<now-300000 || sent>now+300000 || !equal(id,Json.text(Json.read(new String(bytes,StandardCharsets.UTF_8)),"requestId")))throw new IllegalArgumentException("Invalid signed request");
                Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(p.sharedSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                String signature=Base64.getEncoder().encodeToString(mac.doFinal((id+timestamp+Json.sha(bytes)).getBytes(StandardCharsets.UTF_8)));
                if(!equal(signature,req.getHeader("X-KB-Signature")))throw new IllegalArgumentException("Invalid signature");
                request=new HttpServletRequestWrapper(req) {
                    @Override public ServletInputStream getInputStream() {
                        var stream=new ByteArrayInputStream(bytes);
                        return new ServletInputStream() {
                            @Override public int read(){return stream.read();}
                            @Override public boolean isFinished(){return stream.available()==0;}
                            @Override public boolean isReady(){return true;}
                            @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
                        };
                    }
                    @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
                };
            }
        } catch(Exception e) {
            res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write(Json.write(Map.of("code","UNAUTHENTICATED","message","服务凭据或签名无效")));return;
        }
        chain.doFilter(request,res);
    }
}
