package com.lvt4j.socketproxy.auth;

import java.util.Map;

/**
 * 认证器类型<br>
 * 每种类型对应一种认证方式，配置中以{@code auth.authors[].type}指定。
 *
 * @author LV on 2026年8月31日
 */
public enum AuthorType {

    /**
     * 本地token认证：凭据保存在本地文件中，可通过管理界面维护
     * @see LocalTokenAuthor
     */
    LocalToken {
        @Override
        public Author build(Map<String, String> args) {
            return new LocalTokenAuthor(args);
        }
    },

    /**
     * ldap认证：向ldap服务校验用户名密码
     * @see LdapAuthor
     */
    Ldap {
        @Override
        public Author build(Map<String, String> args) {
            return new LdapAuthor(args);
        }
    },
    ;

    /** 按配置参数构建认证器实例 */
    public abstract Author build(Map<String, String> args);

    public static AuthorType parse(String type) {
        for(AuthorType t : values()){
            if(t.name().equalsIgnoreCase(type)) return t;
        }
        return null;
    }

}
