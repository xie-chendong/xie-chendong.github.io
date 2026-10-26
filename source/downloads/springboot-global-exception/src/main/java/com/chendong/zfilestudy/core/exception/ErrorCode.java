package com.chendong.zfilestudy.core.exception;

import lombok.Getter;

/**
 * 异常信息枚举类
 * @Author: xiechendong
 * @Description:
 * @Date: 2026/04/19 19:53
 */
@Getter
public enum ErrorCode {
    /**
    * 系统异常 5xxxx
    */
    SYSTEM_ERROR("50000", "系统异常"),

    DEMO_SITE_DISABLE_OPERATOR("50002", "演示站点不允许此操作"),


    /**
    * 业务异常 4xxxx
     * 第二位为 0 时，是系统初始化相关错误
     * 第二位为 1 时，是前台(文件管理)错误
     * 第二位为 2 时，是登录错误
     * 第二位为 3 时，是管理员端错误
     * 第二位为 4 时，是即时通讯(IM)错误*/
    BIZ_ERROR("40000", "操作失败"),
    BIZ_NOT_FOUND("40400","NOT FOUND"),

    BIZ_SYSTEM_ALREADY_INIT("40001", "系统已初始化，请勿重复初始化"),
    BIZ_SYSTEM_INIT_ERROR("40002", "系统初始化错误"),

    // 前台错误
    BIZ_ACCESS_TOO_FREQUENT("41005", "请求太频繁了，请稍后再试"),
    BIZ_FILE_PATH_ILLEGAL("41009", "文件名/路径非法"),
    BIZ_FOLDER_NOT_EXIST("41026", "文件夹不存在"),
    BIZ_FILE_NOT_EXIST("41027", "文件不存在"),
    BIZ_INVALID_SIGNATURE("41028", "下载签名无效或已过期"),
    BIZ_UNSUPPORTED_PROXY_DOWNLOAD("41029", "该存储源不支持代理下载"),
    BIZ_OFFICE_PREVIEW_FAILED("41030", "Office 文件预览失败"),
    BIZ_OFFICE_PREVIEW_TOO_LARGE("41031", "Office 文件超过在线预览大小限制"),
    BIZ_FILE_ALREADY_EXISTS("41032", "目标文件或文件夹已存在"),
    BIZ_FILE_OPERATION_FAILED("41033", "文件操作失败"),

    // 登录错误
    BIZ_UNAUTHORIZED("42000", "未登录或授权"),
    BIZ_LOGIN_ERROR("42001", "登录失败，账号或密码错误"),
    BIZ_VERIFY_CODE_ERROR("42002", "验证码错误或已失效"),

    // 管理员端错误
    BIZ_ADMIN_ERROR("43000", "操作失败"),
    BIZ_STORAGE_INIT_ERROR("43011", "存储源初始化失败"),
    BIZ_STORAGE_SAVE_OR_UPDATE_ERROR("43012", "存储源保存或更新失败"),
    BIZ_STORAGE_CONFIG_SAVE_ERROR("43013", "存储源配置保存失败"),

    // 即时通讯错误
    BIZ_IM_CONVERSATION_NOT_EXIST("44000", "会话不存在"),
    BIZ_IM_NOT_CONVERSATION_MEMBER("44001", "无权访问该会话"),
    BIZ_IM_TARGET_USER_NOT_EXIST("44002", "目标用户不存在"),
    BIZ_IM_MESSAGE_TYPE_ILLEGAL("44003", "消息类型非法"),
    BIZ_IM_CHAT_WITH_SELF("44004", "不能和自己创建会话"),

    // 权限管理错误
    BIZ_PERMISSION_ERROR("45000", "权限管理操作失败"),
    BIZ_PERMISSION_ROLE_CODE_DUPLICATE("45001", "角色编码已存在"),
    BIZ_PERMISSION_BUILTIN_PROTECTED("45002", "内置角色不允许该操作"),
    BIZ_PERMISSION_ROLE_NOT_EXIST("45003", "角色不存在"),

    // 短链错误
    BIZ_SHORT_LINK_NOT_EXIST("46000", "短链不存在"),
    BIZ_SHORT_LINK_EXPIRED("46001", "短链已过期"),
    BIZ_SHORT_LINK_EXPIRE_TIME_ILLEGAL("46002", "无效的短链有效时长"),

    /**
     * 通用的无权限异常
     */
    NO_FORBIDDEN("30000","没有权限"),
    NO_CUSTOM_SHARE_LINK_KEY_PERMISSION("30001","没有自定义分享链接 key 的权限");


    private String code;
    private String message;
    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

}
