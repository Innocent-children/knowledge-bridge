package com.openclaw.kbbridge;

public class ProcessingFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public ProcessingFailure(String code,String message,boolean retryable) {super(message);this.code=code;this.retryable=retryable;}
    public String code() {return code;}
    public boolean retryable() {return retryable;}
    public static ProcessingFailure from(Throwable error) {
        if(error instanceof ProcessingFailure failure)return failure;
        if(error instanceof org.springframework.web.server.ResponseStatusException status && status.getStatusCode().is4xxClientError())
            return new ProcessingFailure("PROCESSING_REJECTED","处理请求被拒绝，请检查配置或内容",false);
        return new ProcessingFailure("PROCESSING_UNAVAILABLE","处理服务暂时不可用，后台将重试",true);
    }
}
