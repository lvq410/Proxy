package com.lvt4j.socketproxy.auth;

/**
 * 认证器<br>
 * 在配置中以{@code auth.authors}声明，由使用方(如pws代理)通过{@code authorId}引用。
 *
 * @author LV on 2026年8月31日
 */
public interface Author {

    /**
     * 校验用户凭据
     * @param username 用户名，不会为null
     * @param credential 凭据(密码/token)，不会为null
     * @return true表示认证通过
     */
    boolean authenticate(String username, String credential);

    /**
     * 释放认证器占用的资源，配置变更导致认证器被替换时调用。
     * 无资源需释放时无需重写。
     */
    default void destory() {}

}
