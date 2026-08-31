package com.lvt4j.socketproxy.auth;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Entry;

import lombok.SneakyThrows;

/**
 * 测试用的内存LDAP服务，供{@link LdapAuthor}的测试使用，
 * 目录结构与常见的企业LDAP保持一致：用户位于{@link #BaseDn}下，以uid标识。
 *
 * @author LV on 2026年8月28日
 */
public class EmbeddedLdap {

    public static final String BaseDn = "ou=people,dc=example,dc=com";
    /** 用于搜索用户的只读账号 */
    public static final String BindDn = "cn=readonly,dc=example,dc=com";
    public static final String BindPassword = "readonly-pass";

    private static final String RootDn = "dc=example,dc=com";

    private final InMemoryDirectoryServer server;
    private final int port;

    @SneakyThrows
    public EmbeddedLdap(int port) {
        this.port = port;

        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(RootDn);
        // 只读账号作为额外的bind账号，模拟“先用只读账号bind再搜索用户”的流程
        config.addAdditionalBindCredentials(BindDn, BindPassword);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", port));
        // 关掉schema校验，避免为测试用户补齐一堆必填属性
        config.setSchema(null);

        server = new InMemoryDirectoryServer(config);
        server.add(new Entry("dn: "+RootDn, "objectClass: top", "objectClass: domain", "dc: example"));
        server.add(new Entry("dn: "+BaseDn, "objectClass: top", "objectClass: organizationalUnit", "ou: people"));
        server.startListening();
    }

    /** 添加一个用户 */
    @SneakyThrows
    public void addUser(String uid, String password) {
        server.add(new Entry("dn: uid="+uid+","+BaseDn,
            "objectClass: top",
            "objectClass: person",
            "objectClass: inetOrgPerson",
            "uid: "+uid,
            "cn: "+uid,
            "sn: "+uid,
            "userPassword: "+password));
    }

    public String url() {
        return "ldap://127.0.0.1:"+port;
    }

    public void shutdown() {
        server.shutDown(true);
    }

}
