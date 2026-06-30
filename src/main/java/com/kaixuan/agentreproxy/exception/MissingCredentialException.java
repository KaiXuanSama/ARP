package com.kaixuan.agentreproxy.exception;

/**
 * 账户缺少任何可用凭证（accessToken 与 apiKey 都不存在）时抛出
 * <p>
 * 由 controller 捕获后转 400 响应
 */
public class MissingCredentialException extends RuntimeException {
    public MissingCredentialException(String message) {
        super(message);
    }
}
