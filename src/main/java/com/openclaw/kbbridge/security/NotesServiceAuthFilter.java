package com.openclaw.kbbridge.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class NotesServiceAuthFilter extends OncePerRequestFilter {
    private final String token;
    public NotesServiceAuthFilter(String token){this.token=token;}
    static boolean isNotesPath(HttpServletRequest request){
        String path=request.getServletPath();
        if(path==null||path.isEmpty())path=request.getRequestURI().replaceAll(";[^/]*","");
        return path.startsWith("/api/v1/notes/");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !isNotesPath(request);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String authorization=request.getHeader("Authorization");
        if(token.isBlank()||authorization==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8))){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"code\":\"SERVICE_UNAUTHENTICATED\"}");return;}
        if(request.getContentLengthLong()>12582912){response.sendError(413);return;}
        response.setHeader("Cache-Control","no-store");chain.doFilter(request,response);
    }
}
