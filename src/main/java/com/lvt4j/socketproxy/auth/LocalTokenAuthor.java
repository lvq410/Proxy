package com.lvt4j.socketproxy.auth;

import java.util.Map;

import org.apache.commons.lang3.Validate;

/**
 * 本地token认证器：凭据保存在本地文件，可通过管理界面维护。
 * <p>
 * 配置例：
 * <pre>
 * auth:
 *   authors:
 *   - id: local-token
 *     type: LocalToken
 *     args:
 *       userStore: data/users.json
 * </pre>
 *
 * @author LV on 2026年8月31日
 */
public class LocalTokenAuthor implements Author {

    /** 凭据存储文件路径 */
    public static final String Arg_UserStore = "userStore";

    private final UserStore store;

    public LocalTokenAuthor(Map<String, String> args) {
        String path = args.get(Arg_UserStore);
        Validate.notBlank(path, "LocalToken认证器，args.%s不能为空", Arg_UserStore);
        this.store = new UserStore(path);
    }

    @Override
    public boolean authenticate(String username, String credential) {
        return store.authenticate(username, credential);
    }

    /** 供管理界面操作凭据 */
    public UserStore store() {
        return store;
    }

}
