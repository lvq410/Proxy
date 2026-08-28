package com.lvt4j.socketproxy;

/**
 * pws服务端连接认证器<br>
 * 客户端在websocket握手时通过{@code Authorization: Basic base64(user:pass)}头携带凭据，
 * 服务端解析后交由本接口校验，校验不通过则拒绝建立连接。
 *
 * @author LV on 2026年8月28日
 */
public interface PwsAuthenticator {

    /**
     * 校验用户凭据
     * @param username 用户名，不会为null
     * @param password 密码，不会为null
     * @return true表示认证通过
     */
    boolean authenticate(String username, String password);

}
