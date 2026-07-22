package com.parkenergyplatform.aop;

import java.time.LocalDateTime;
import java.util.Arrays;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.entity.OperationLogEntity;
import com.parkenergyplatform.mapper.OperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class OperationLogAspect {
    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    public OperationLogAspect(OperationLogMapper operationLogMapper, ObjectMapper objectMapper) {
        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        long start = System.currentTimeMillis();
        int status = 1;
        String error = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable ex) {
            status = 0;
            error = ex.getMessage();
            throw ex;
        } finally {
            saveLog(joinPoint, operationLog, (int) (System.currentTimeMillis() - start), status, error);
        }
    }

    private void saveLog(ProceedingJoinPoint joinPoint, OperationLog operationLog, int costTime, int status, String error) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest request = attrs == null ? null : attrs.getRequest();
            OperationLogEntity entity = new OperationLogEntity();
            entity.setUserId(StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null);
            entity.setUsername(StpUtil.isLogin() ? String.valueOf(StpUtil.getSession().get("username")) : null);
            entity.setModule(operationLog.module());
            entity.setOperation(operationLog.operation());
            entity.setMethod(request == null ? null : request.getMethod());
            entity.setRequestUrl(request == null ? null : request.getRequestURI());
            entity.setRequestParam(toJson(joinPoint.getArgs()));
            entity.setIpAddress(clientIp(request));
            entity.setCostTime(costTime);
            entity.setStatus(status);
            entity.setErrorMsg(error);
            entity.setCreateTime(LocalDateTime.now());
            operationLogMapper.insert(entity);
        } catch (Exception ignored) {
            // 操作日志不能影响主业务请求。
        }
    }

    private String toJson(Object[] args) {
        Object[] filtered = Arrays.stream(args)
                .filter(arg -> !(arg instanceof HttpServletRequest))
                .filter(arg -> !(arg instanceof HttpServletResponse))
                .toArray();
        try {
            String json = objectMapper.writeValueAsString(filtered);
            return json.length() > 1800 ? json.substring(0, 1800) : json;
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
