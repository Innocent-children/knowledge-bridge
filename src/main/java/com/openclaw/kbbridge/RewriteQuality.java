package com.openclaw.kbbridge;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class RewriteQuality {
    private static final String CHUNK_SEPARATOR="---CHUNK---";
    private static final Pattern FENCE=Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern ATTACHMENT=Pattern.compile("attachment://([0-9a-fA-F-]{36})");
    private static final Pattern EXTERNAL_IMAGE=Pattern.compile("!\\[[^\\]]*]\\(\\s*<?(?:https?://|data:)",Pattern.CASE_INSENSITIVE);
    public void validate(String content,Set<String> allowed) {
        if(content==null || content.isBlank() || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4194304)
            throw new ProcessingFailure("INVALID_REWRITE","生成内容为空或超过大小限制",false);
        if(content.codePoints().anyMatch(c->c==0xfffd || c==0xfffe || c==0xffff || c<32 && c!='\n' && c!='\r' && c!='\t'))
            throw new ProcessingFailure("INVALID_REWRITE","生成内容包含异常字符",false);
        char character=0;int length=0;boolean hasContent=false;
        for(String line:content.split("\\R",-1)) {
            if(line.contains(CHUNK_SEPARATOR)) {
                if(character!=0 || !line.stripTrailing().equals(CHUNK_SEPARATOR) || !hasContent)
                    throw new ProcessingFailure("INVALID_REWRITE_CHUNKS","分块标记必须单独顶格一行、位于代码块之外，且不能产生空块",false);
                hasContent=false;continue;
            }
            if(!line.isBlank())hasContent=true;
            var match=FENCE.matcher(line);if(!match.matches())continue;
            String fence=match.group(1),tail=match.group(2);
            if(character==0) {character=fence.charAt(0);length=fence.length();}
            else if(fence.charAt(0)==character && fence.length()>=length && tail.isBlank()) {character=0;length=0;}
        }
        if(!hasContent)throw new ProcessingFailure("INVALID_REWRITE_CHUNKS","生成内容不能以分块标记结束",false);
        if(character!=0)throw new ProcessingFailure("INCOMPLETE_REWRITE","生成的代码块未闭合，不能确认入库",false);
        var references=ATTACHMENT.matcher(content);
        while(references.find())if(!allowed.contains(references.group(1).toLowerCase(Locale.ROOT)))
            throw new ProcessingFailure("INVALID_REWRITE_ATTACHMENT","生成内容引用了原文以外的附件",false);
        if(EXTERNAL_IMAGE.matcher(content).find())throw new ProcessingFailure("INVALID_REWRITE_IMAGE","生成内容包含不允许的外链图片",false);
    }
}
