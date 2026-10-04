package com.openclaw.kbbridge.unified;
import java.util.UUID;

public final class UnifiedPaths {
    private UnifiedPaths() {}
    public static String id(String value) {
        String canonical=UUID.fromString(value).toString();
        if(value.length()!=36||!canonical.equalsIgnoreCase(value))throw new IllegalArgumentException("Invalid UUID");
        return canonical;
    }
    public static String base(String document) {
        return "documents/"+id(document)+"/";
    }
    public static String source(String document,long revision) {
        if (revision<1) throw new IllegalArgumentException("Revision must be positive");
        return base(document)+"source/"+revision+"/source.md";
    }
    public static String derived(String document,long revision,String job,String part) {
        if (!part.equals("guide")&&!part.equals("qa")) throw new IllegalArgumentException("Invalid output");
        return base(document)+"derived/"+revision+"/"+id(job)+"/"+part+".md";
    }
    public static String release(String document,String release) {
        return base(document)+"releases/"+id(release)+"/document.md";
    }
}
