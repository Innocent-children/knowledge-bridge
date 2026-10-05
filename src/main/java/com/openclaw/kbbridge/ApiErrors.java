package com.openclaw.kbbridge;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) {return ResponseEntity.unprocessableContent().body(Map.of("code","INVALID_INPUT","message",e.getMessage()));}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).body(Map.of("code","OPERATION_REJECTED","message",e.getReason()==null?"操作被拒绝":e.getReason()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(IllegalStateException e) {return ResponseEntity.status(503).body(Map.of("code","DEPENDENCY_UNAVAILABLE","message","处理服务暂时不可用"));}
}
