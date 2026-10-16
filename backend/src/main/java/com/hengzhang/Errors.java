package com.hengzhang;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import static com.hengzhang.Domain.*;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(Problem.class) ResponseEntity<?> problem(Problem e) { return response(409,e.getMessage()); }
    @ExceptionHandler(AuthenticationException.class) ResponseEntity<?> auth(AuthenticationException e) { return response(401,"账号或密码错误"); }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var error=e.getBindingResult().getFieldErrors().getFirst(); return response(400,error.getField()+": "+error.getDefaultMessage());
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<?> invalid(Exception e) { return response(400,"请求参数或数据格式不正确"); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> tooLarge(Exception e) { return response(413,"文件不能超过 5 MB"); }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> duplicate(Exception e) { return response(409,"记录重复或引用关系无效，请检查渠道编码和关联数据"); }
    @ExceptionHandler(ArithmeticException.class) ResponseEntity<?> overflow(Exception e) { return response(409,"金额合计超出系统范围，本次操作未提交"); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Request processing failed",e);
        return response(500,"服务处理失败，请检查服务日志后重试");
    }
    private ResponseEntity<?> response(int status,String message) { return ResponseEntity.status(status).body(Map.of("message",message)); }
}
