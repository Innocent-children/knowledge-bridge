package com.openclaw.kbbridge.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class NotesServiceAuthFilterTest {
    @Test void oldSharedSecretAndAbsentServiceTokenAreRejected()throws Exception{
        for(String token:new String[]{"","synthetic"}){
            var filter=new NotesServiceAuthFilter(token);var req=new MockHttpServletRequest("POST","/api/v1/notes/rewrite");req.addHeader("X-KB-Signature","old-service-signature");var res=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(req,res,chain);assertEquals(401,res.getStatus());assertNull(chain.getRequest());
        }
    }
    @Test void validNotesServiceTokenReachesController()throws Exception{
        var filter=new NotesServiceAuthFilter("synthetic");var req=new MockHttpServletRequest("POST","/api/v1/notes/rewrite");req.addHeader("Authorization","Bearer synthetic");var chain=new MockFilterChain();filter.doFilter(req,new MockHttpServletResponse(),chain);assertNotNull(chain.getRequest());
    }
    @Test void matrixParametersCannotSelectTheLegacyAuthenticationPath()throws Exception{
        var filter=new NotesServiceAuthFilter("synthetic");
        var req=new MockHttpServletRequest("POST","/api/v1/notes/rewrite;ignored=yes");
        req.addHeader("X-KB-Signature","old-service-signature");
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();
        filter.doFilter(req,response,chain);
        assertEquals(401,response.getStatus());assertNull(chain.getRequest());
    }
}
