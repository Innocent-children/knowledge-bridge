package com.openclaw.kbbridge;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
public final class Json {
    private static final JsonMapper MAPPER=JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private Json() {}
    public static String write(Object value) { return MAPPER.writeValueAsString(value); }
    public static Map<String,Object> read(String value) { return MAPPER.readValue(value,new TypeReference<Map<String,Object>>(){}); }
    public static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    public static String sha(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch(Exception e) { throw new IllegalStateException(e); } }
    public static String id(Object value) { return UUID.fromString(String.valueOf(value)).toString(); }
    public static String text(Map<String,Object> row,String key) { Object v=row.get(key);return v==null?null:v.toString(); }
    public static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
}
