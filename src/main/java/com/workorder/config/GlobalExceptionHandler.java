package com.workorder.config;

import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity
                .ok(Result.fail(e.getErrorCode(), e.getMessage()));
    }

    /** Sa-Token 权限不足（@SaCheckPermission 拦截）→ 统一返回 403，而非落到 Exception 的 500 */
    @ExceptionHandler(cn.dev33.satoken.exception.NotPermissionException.class)
    public ResponseEntity<Result<Void>> handleNotPermission(cn.dev33.satoken.exception.NotPermissionException e) {
        log.warn("Sa-Token 无权限拦截: {}", e.getMessage());
        return ResponseEntity
                .ok(Result.fail(ErrorCode.FORBIDDEN, e.getMessage()));
    }

    /** Sa-Token 未登录 / 登录态失效（@SaCheckLogin 拦截）→ 401 */
    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(cn.dev33.satoken.exception.NotLoginException e) {
        log.warn("Sa-Token 未登录拦截: {}", e.getMessage());
        return ResponseEntity
                .ok(Result.fail(ErrorCode.UNAUTHORIZED, "未登录或登录已过期"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().get(0);
        String message = fieldError.getDefaultMessage();
        log.warn("参数校验失败: field={}, message={}", fieldError.getField(), message);
        return ResponseEntity
                .ok(Result.fail(ErrorCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().get(0);
        String message = fieldError.getDefaultMessage();
        log.warn("参数绑定失败: field={}, message={}", fieldError.getField(), message);
        return ResponseEntity
                .ok(Result.fail(ErrorCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常: ", e);
        return ResponseEntity
                .ok(Result.fail(ErrorCode.INTERNAL_ERROR, "服务器内部错误"));
    }
}
