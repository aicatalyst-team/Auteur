package com.auteur.web;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "VALIDATION_FAILED");
        body.put("message", e.getBindingResult().getAllErrors().stream()
                .map(err -> err.getDefaultMessage()).toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NOT_FOUND", "message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        log.warn("IllegalStateException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "UPSTREAM_PARSE_FAILED", "message", String.valueOf(e.getMessage())));
    }

    /**
     * 浏览器在下载中关连接 (用户暂停 video / 跳走 / HMR 刷新)。
     * 响应已经在写,channel 关了,服务端没法再回任何 body —— 不算错误,降到 debug 级别静默吃掉。
     * 不处理的话: 1) 误打成 ERROR 噪日志 2) generic() 想包成 JSON 又撞上 'video/mp4' 触发二次 HMNW.
     */
    @ExceptionHandler(ClientAbortException.class)
    public void clientAbort(ClientAbortException e) {
        log.debug("client aborted connection: {}", e.getMessage());
    }

    /**
     * SSE / async 请求里:用户切页/刷新/调 cancel 端点 → fetch abort → socket 已断,
     * 但 Spring 在 dispatch 阶段才发现并抛 AsyncRequestNotUsableException。这是正常取消路径,
     * 不算错误。如果 generic() 接住会再一次因 Content-Type=text/event-stream 没 converter 二次报错。
     * 跟 ClientAbortException 同处理:debug 日志静默吞掉。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void asyncNotUsable(AsyncRequestNotUsableException e) {
        log.debug("async response no longer usable (client likely disconnected): {}", e.getMessage());
    }

    /**
     * 客户端断开时除了 AsyncRequestNotUsableException,Spring 还可能直接冒泡 java.io.IOException(Broken pipe / Connection reset)
     * 走 servlet 异常处理链。这种是网络层正常的"对端关闭",不是真错误,静默。
     * 注意:只静默"客户端断"模式;别的真 IO 错误(磁盘读失败之类)按字面 message 不会匹配,会 fallthrough 给 generic()。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> ioException(IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("Broken pipe") || msg.contains("Connection reset")
                || msg.contains("aborted") || msg.contains("已中止")) {
            log.debug("client disconnected (IOException): {}", msg);
            return null; // 写不回去也不必写;ResponseEntity null 让 Spring 不再尝试 serialize
        }
        // 真 IO 错误,500
        log.error("Unhandled IOException", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "IO_ERROR", "message", msg));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of(
                        "error", e.getStatusCode().toString(),
                        "message", String.valueOf(e.getReason())
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "INTERNAL", "message", String.valueOf(e.getMessage())));
    }
}
