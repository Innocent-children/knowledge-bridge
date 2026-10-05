package com.openclaw.kbbridge;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(20)
public class RequestLimits extends OncePerRequestFilter {
    private record Window(long minute,int count) {}
    private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
    private final BridgeProperties properties;
    public RequestLimits(BridgeProperties properties) {this.properties=properties;}
    public boolean allowed(String key,int limit,long now) {
        long minute=now/60000;
        if(windows.size()>10000)windows.entrySet().removeIf(entry->entry.getValue().minute()<minute);
        var window=windows.compute(key,(ignored,prior)->prior==null || prior.minute()!=minute?new Window(minute,1):new Window(minute,prior.count()+1));
        return window.count()<=limit;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI();int limit=0;String category="";
        if(path.equals("/api/v1/query")){limit=properties.queryRequestsPerMinute();category="query";}
        else if(path.equals("/api/v1/ingest/manual") || path.equals("/api/v1/ingest/file") || path.equals("/api/v1/ingest/candidate")){limit=properties.ingestRequestsPerMinute();category="ingest";}
        if(limit>0 && !allowed(category+":"+request.getRemoteAddr(),limit,System.currentTimeMillis())) {
            response.setStatus(429);response.setHeader("Retry-After","60");response.setContentType("application/json;charset=UTF-8");response.getWriter().write(Json.write(java.util.Map.of("code","RATE_LIMITED","message","请求过于频繁，请稍后再试")));return;
        }
        chain.doFilter(request,response);
    }
}
