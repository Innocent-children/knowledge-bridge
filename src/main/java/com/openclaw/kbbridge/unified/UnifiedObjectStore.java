package com.openclaw.kbbridge.unified;

import io.minio.*;
import io.minio.messages.Item;
import io.minio.errors.ErrorResponseException;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class UnifiedObjectStore {
    private final MinioClient client;
    private final UnifiedProperties properties;
    public UnifiedObjectStore(MinioClient client,UnifiedProperties properties) { this.client=client;this.properties=properties; }
    public static String sha(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String sha(String content) { return sha(content.getBytes(StandardCharsets.UTF_8)); }
    private void key(String key) {
        if (!"kb-content".equals(properties.getBucket())) throw new IllegalStateException("Unified bucket must be kb-content");
        if (!key.startsWith("documents/")||key.contains("..")||key.contains("\\")||key.length()>512)
            throw new IllegalArgumentException("Invalid unified object key");
    }
    public byte[] get(String key,int limit) {
        key(key);
        try (var response=client.getObject(GetObjectArgs.builder().bucket(properties.getBucket()).object(key).build())) {
            byte[] content=response.readNBytes(limit+1);
            if (content.length>limit) throw new IllegalArgumentException("Object too large");
            return content;
        } catch (Exception ex) { throw new IllegalStateException("Cannot read registered knowledge object",ex); }
    }
    public void put(String key,byte[] bytes,String type) {
        key(key);
        try {
            boolean exists=true;
            try { client.statObject(StatObjectArgs.builder().bucket(properties.getBucket()).object(key).build()); }
            catch(ErrorResponseException ex) {
                if (Set.of("NoSuchKey","NoSuchObject","NotFound").contains(ex.errorResponse().code())) exists=false;
                else throw ex;
            }
            if (exists) {
                if (!sha(get(key,Math.max(bytes.length,1))).equals(sha(bytes))) throw new IllegalStateException("Immutable object conflict");
                return;
            }
            client.putObject(PutObjectArgs.builder().bucket(properties.getBucket()).object(key)
                .stream(new ByteArrayInputStream(bytes),(long)bytes.length,-1L).contentType(type).build());
            if (!sha(get(key,Math.max(bytes.length,1))).equals(sha(bytes))) throw new IllegalStateException("Object verification failed");
        } catch(Exception ex) { throw new IllegalStateException("Cannot store immutable knowledge object",ex); }
    }
    public void putText(String key,String content) { put(key,content.getBytes(StandardCharsets.UTF_8),"text/markdown; charset=utf-8"); }
    public void deleteDocument(String document) {
        String prefix=UnifiedPaths.base(document);
        key(prefix);
        for (Result<Item> item:client.listObjects(ListObjectsArgs.builder().bucket(properties.getBucket()).prefix(prefix).recursive(true).build())) {
            try { client.removeObject(RemoveObjectArgs.builder().bucket(properties.getBucket()).object(item.get().objectName()).build()); }
            catch(Exception ex) { throw new IllegalStateException("Document object cleanup incomplete",ex); }
        }
    }
}
