package com.potatotv.pacc.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 仅超级管理员可访问。用于「文档与协议」这类内部技术资料接口：
 * 前端按 role 重定向只是 UX 优化，真正的越权拦截必须落在服务端，
 * 否则非超管可绕过页面直接调 API 拿到检测原理、反制手段等内容。
 * 由 {@link com.potatotv.pacc.config.PermissionInterceptor} 依据 {@code adminRole} 判定。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SuperAdminOnly {
}
