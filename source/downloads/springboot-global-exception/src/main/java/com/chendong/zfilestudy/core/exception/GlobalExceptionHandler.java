package com.chendong.zfilestudy.core.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.chendong.zfilestudy.core.exception.biz.FilePathSecurityBizException;
import com.chendong.zfilestudy.core.exception.biz.InitializeStorageSourceBizException;
import com.chendong.zfilestudy.core.exception.core.BizException;
import com.chendong.zfilestudy.core.exception.status.BadRequestAccessException;
import com.chendong.zfilestudy.core.exception.status.ForbiddenAccessException;
import com.chendong.zfilestudy.core.exception.status.UnauthorizedAccessException;
import com.chendong.zfilestudy.core.util.AjaxJson;
import com.chendong.zfilestudy.core.util.RequestHolder;
import com.chendong.zfilestudy.core.util.stringUtil.StringUtils;
import com.chendong.zfilestudy.module.config.service.SystemConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 全局异常处理
 * @Author: xiechendong
 * @Description:
 * @Date: 2026/04/20 19:41
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @Resource
    private SystemConfigService systemConfigService;

    @ExceptionHandler(value = BizException.class)
    @ResponseStatus
    @ResponseBody
    public AjaxJson<?> bizException(BizException ex){
        return new AjaxJson<>(ex.getCode(),ex.getMessage());
    }

    /**
     * 未授权访问处理，如果是 axios 请求则返回 401，否则重定向到未授权访问的 URL
     * @return {@link AjaxJson }<{@link ? }>
     */
    @ExceptionHandler(value = UnauthorizedAccessException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public AjaxJson<?> unauthorizedAccessException(){
        // 判断是否是 axios 请求，axios请求一般是调用后台api不需要刷新页面，直接返回JSON格式数据，是则返回 401
        if(RequestHolder.isAxiosRequest()){
            return AjaxJson.getUnauthorizedResult();
        }
        // 否则需要重定向到错误页
        try{
            String unauthorizedUrl = systemConfigService.getUnauthorizedUrl();
            RequestHolder.getResponse().sendRedirect(unauthorizedUrl);
        }catch (IOException ex){
            return AjaxJson.getUnauthorizedResult();
        }
        return null;
    }

    /**
     * 无权限禁止访问异常
     *
     * @return AjaxJson <?>
     * @author xiechendong
     * @date 2026/09/23 17:33:18
     */
    @ExceptionHandler(value = {NotRoleException.class, NotPermissionException.class})
    @ResponseBody
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public AjaxJson<?> forbiddenAccessException() {
        if (RequestHolder.isAxiosRequest() || RequestHolder.getRequest().getRequestURI().startsWith("/pd/")) {
            return AjaxJson.getForbiddenResult();
        }
        try {
            String forbiddenUrl = systemConfigService.getForbiddenUrl();
            RequestHolder.getResponse().sendRedirect(forbiddenUrl);
        } catch (IOException ex) {
            return AjaxJson.getForbiddenResult();
        }
        return null;
    }

    /**
     * 错误请求访问异常，表示请求参数有误或者服务器无法理解，返回400状态码
     *
     * @param ex 捕获的异常
     * @return {@code AjaxJson<?> }
     * @author xiecd
     * @date 2026/07/04 23:11:29
     */
    @ExceptionHandler(value = BadRequestAccessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AjaxJson<?> badRequestAccessException(BadRequestAccessException ex) {
        return new AjaxJson<>(ex.getCode(), ex.getMessage());
    }

    /**
     * 方法参数无效异常：处理@Valid注解验证失败的异常
     *
     * @param ex 捕获的异常
     * @return {@code AjaxJson<?> }
     * @author xiecd
     * @date 2026/07/04 23:11:34
     */
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AjaxJson<?> methodArgumentNotValidException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数错误");
        return AjaxJson.getError(message);
    }

    /**
     * 处理未登录异常
     *
     * @param ex 异常
     * @return AjaxJson
     * @author xiechendong
     * @date 2026/08/25 23:54:42
     */
    @ExceptionHandler(value = NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ResponseBody
    public AjaxJson<?> handleNotLoginException(NotLoginException ex){
        if(RequestHolder.isAxiosRequest()){
            return AjaxJson.getUnauthorizedResult();
        }
        try{
            String domain = systemConfigService.getRealFrontDomain();
            if(StringUtils.isBlank(domain)){
                domain = "";
            }
            String loginUrl = StringUtils.pathConcat(domain, "/login");
            RequestHolder.getResponse().sendRedirect(loginUrl);
        }catch (IOException e){
            return AjaxJson.getUnauthorizedResult();
        }
        return null;
    }

    /**
     * 禁止访问异常
     *
     * @param ex 异常
     * @return AjaxJson
     * @author xiechendong
     * @date 2026/08/26 00:08:15
     */
    @ExceptionHandler(value = ForbiddenAccessException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public AjaxJson<?> forbiddenAccessException(ForbiddenAccessException ex){
        if(RequestHolder.isAxiosRequest()) {
            return AjaxJson.getError(ex.getCode(),ex.getMessage());
        }
        try {
            String forbiddenUrl = systemConfigService.getForbiddenUrl(ex.getCode(), ex.getMessage());
            RequestHolder.getResponse().sendRedirect(forbiddenUrl);
        } catch (IOException e) {
            return AjaxJson.getError(ex.getCode(),ex.getMessage());
        }
        return null;
    }

    @ExceptionHandler(value = InitializeStorageSourceBizException.class)
    @ResponseStatus
    public AjaxJson<?> initializeStorageSourceBizException(InitializeStorageSourceBizException ex){
        log.error("初始化存储源异常，存储源ID：{}", ex.getStorageId(), ex);
        return AjaxJson.getError(ex.getCode(), "存储源初始化失败：" + ex.getMessage());
    }

    @ExceptionHandler(value = FilePathSecurityBizException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AjaxJson<String> filePathSecurityBizException(FilePathSecurityBizException ex){
        log.warn("文件路径安全检查异常，路径：{}", ex.getPath());
        return new AjaxJson<>(ex.getCode(), ex.getMessage());
    }
}
