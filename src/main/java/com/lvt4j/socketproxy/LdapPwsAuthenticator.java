package com.lvt4j.socketproxy;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;

import org.apache.commons.lang3.StringUtils;

import com.lvt4j.socketproxy.Config.LdapAuthConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * 基于LDAP的{@link PwsAuthenticator}实现，采用标准的“两次bind”校验流程：
 * <ol>
 * <li>用只读账号({@link LdapAuthConfig#bindDn})bind，在{@link LdapAuthConfig#baseDn}下
 *     按{@link LdapAuthConfig#userFilter}搜索出用户的DN</li>
 * <li>用上一步搜到的用户DN+用户密码再次bind，bind成功即认证通过</li>
 * </ol>
 * 之所以要搜索而非直接拼接用户DN，是因为不同LDAP目录的用户条目层级可能不同，
 * 搜索能适配子树中任意位置的用户条目。
 * <p>
 * 仅依赖JDK自带的JNDI({@code javax.naming})，不引入额外依赖。
 * <p>
 * 为避免每次建立连接都请求LDAP，认证通过的结果会按{@link LdapAuthConfig#cacheMillis}缓存；
 * 认证失败不缓存，避免用户改密后仍被持续拒绝。
 *
 * @author LV on 2026年8月28日
 */
@Slf4j
public class LdapPwsAuthenticator implements PwsAuthenticator {

    private final LdapAuthConfig config;

    /** 认证通过缓存，key为“用户名\n密码”，value为认证通过的时间戳 */
    private final Map<String, Long> passedCache = new ConcurrentHashMap<>();

    public LdapPwsAuthenticator(LdapAuthConfig config) {
        this.config = config;
    }

    @Override
    public boolean authenticate(String username, String password) {
        // 空密码在部分LDAP实现下会被当作匿名bind从而“认证成功”，必须提前拒绝
        if(StringUtils.isAnyEmpty(username, password)) return false;

        String cacheKey = username+"\n"+password;
        if(config.getCacheMillis()>0){
            Long passedAt = passedCache.get(cacheKey);
            if(passedAt!=null && System.currentTimeMillis()-passedAt<config.getCacheMillis()) return true;
            if(passedAt!=null) passedCache.remove(cacheKey);
        }

        String userDn;
        try{
            userDn = searchUserDn(username);
        }catch(Exception e){
            // 搜索阶段异常通常是LDAP不可达或只读账号配置有误，属于服务端问题，需要暴露出来
            log.error("ldap搜索用户[{}]失败", username, e);
            return false;
        }
        if(userDn==null){
            log.info("ldap认证失败，用户不存在：{}", username);
            return false;
        }

        if(!bind(userDn, password)){
            log.info("ldap认证失败，密码错误：{}", username);
            return false;
        }

        if(config.getCacheMillis()>0) passedCache.put(cacheKey, System.currentTimeMillis());
        log.info("ldap认证通过：{}", username);
        return true;
    }

    /**
     * 用只读账号bind后搜索用户DN
     * @return 用户DN，搜索不到时返回null
     */
    private String searchUserDn(String username) throws Exception {
        DirContext ctx = ctx(config.getBindDn(), config.getBindPassword());
        try{
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setCountLimit(1);
            sc.setReturningAttributes(new String[0]); // 只需要DN，不取任何属性
            // 用占位符传参而非字符串拼接，避免用户名中的特殊字符造成LDAP注入
            NamingEnumeration<SearchResult> rs = ctx.search(config.getBaseDn(), config.getUserFilter(), new Object[]{username}, sc);
            try{
                if(!rs.hasMore()) return null;
                return rs.next().getNameInNamespace();
            }finally{
                try{ rs.close(); }catch(Exception ig){}
            }
        }finally{
            try{ ctx.close(); }catch(Exception ig){}
        }
    }

    /**
     * 尝试以指定DN和密码bind
     * @return bind成功返回true；凭据错误返回false
     */
    private boolean bind(String dn, String password) {
        DirContext ctx = null;
        try{
            ctx = ctx(dn, password);
            return true;
        }catch(AuthenticationException e){
            return false; // 凭据错误，正常的认证失败
        }catch(Exception e){
            log.error("ldap校验[{}]异常", dn, e);
            return false;
        }finally{
            if(ctx!=null) try{ ctx.close(); }catch(Exception ig){}
        }
    }

    private DirContext ctx(String principal, String credentials) throws Exception {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.getUrl());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, credentials);
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(config.getConnectTimeout()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(config.getReadTimeout()));
        return new InitialLdapContext(env, null);
    }

}
