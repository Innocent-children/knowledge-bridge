package com.openclaw.kbbridge;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
@Component
public class ProcessingObjects {
    private final BridgeProperties p;
    private final MinioClient client;
    public ProcessingObjects(BridgeProperties p) { this.p=p;this.client=MinioClient.builder().endpoint(p.minioEndpoint()).credentials(p.minioAccessKey(),p.minioSecretKey()).build(); }
    @jakarta.annotation.PostConstruct public void initialize() throws Exception {
        if(!client.bucketExists(BucketExistsArgs.builder().bucket(p.releasesBucket()).build())) client.makeBucket(MakeBucketArgs.builder().bucket(p.releasesBucket()).build());
    }
    private void check(String key) { if(!key.startsWith("documents/") || key.contains("..") || key.contains("\\") || key.length()>512) throw new IllegalArgumentException("Invalid processing object key"); }
    public void put(String key,String content) {
        check(key);byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        try {
            try {
                client.statObject(StatObjectArgs.builder().bucket(p.releasesBucket()).object(key).build());
                if(!Json.sha(read(key)).equals(Json.sha(bytes))) throw new IllegalStateException("Immutable processing output changed");
                return;
            } catch(ErrorResponseException e) { if(!Set.of("NoSuchKey","NoSuchObject","NotFound").contains(e.errorResponse().code()))throw e; }
            client.putObject(PutObjectArgs.builder().bucket(p.releasesBucket()).object(key).stream(new ByteArrayInputStream(bytes),(long)bytes.length,-1L).contentType("text/markdown; charset=utf-8").build());
        } catch(Exception e) { throw new IllegalStateException("Processing output could not be stored",e); }
    }
    public byte[] read(String key) {
        check(key);
        try(var stream=client.getObject(GetObjectArgs.builder().bucket(p.releasesBucket()).object(key).build())) {
            byte[] bytes=stream.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024)throw new IllegalArgumentException("Output too large");return bytes;
        } catch(Exception e) { throw new IllegalStateException("Processing output unavailable",e); }
    }
    public void deleteDocument(String id) {
        String prefix="documents/"+Json.id(id)+"/";
        try {
            for(var item:client.listObjects(ListObjectsArgs.builder().bucket(p.releasesBucket()).prefix(prefix).recursive(true).build()))
                client.removeObject(RemoveObjectArgs.builder().bucket(p.releasesBucket()).object(item.get().objectName()).build());
        } catch(Exception e) { throw new IllegalStateException("Processing cleanup will retry",e); }
    }
}
