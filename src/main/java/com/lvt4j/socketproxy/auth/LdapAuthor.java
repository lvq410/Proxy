package com.lvt4j.socketproxy.auth;

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
import org.apache.commons.lang3.Validate;

import lombok.extern.slf4j.Slf4j;

/**
 * ldap认证器，采用标准的“两次bind”校验流程：
 * <ol>
 * <li>用只读账号({@link #Arg_BindDn})bind，在{@link #Arg_BaseDn}下
 *     按{@link #Arg_UserFilter}搜索出用户的DN</li>
 * <li>用上一步搜到的用户DN+用户密码再次bind，bind成功即认证通过</li>
 * </ol>
 * 之所以要搜索而非直接拼接用户DN，是因为不同ldap目录的用户条目层级可能不同，
 * 搜索能适配子树中任意位置的用户条目。
 * <p>
 * 仅依赖JDK自带的JNDI({@code javax.naming})，不引入额外依赖。
 * <p>
 * 配置例：
 * <pre>
 * auth:
 *   authors:
 *   - id: my-ldap
 *     type: Ldap
 *     args:
 *       url: ldap://ldap.example.com:389
 *       baseDn: ou=people,dc=example,dc=com
 *       bindDn: cn=readonly,dc=example,dc=com
 *       bindPassword: xxx
 *       userFilter: (uid={0})
 *       cacheMillis: 300000
 * </pre>
 *
 * @author LV on 2026年8月31日
 */
@Slf4j
public class LdapAuthor implements Author {

    public static final String Arg_Url = "url";
    public static final String Arg_BaseDn = "baseDn";
    public static final String Arg_BindDn = "bindDn";
    public static final String Arg_BindPassword = "bindPassword";
    /** 搜索用户的过滤器，{@code {0}}会被替换为待认证的用户名 */
    public static final String Arg_UserFilter = "userFilter";
    /** 认证通过结果的缓存时长(毫秒)，配置为0则不缓存 */
    public static final String Arg_CacheMillis = "cacheMillis";
    public static final String Arg_ConnectTimeout = "connectTimeout";
    public static final String Arg_ReadTimeout = "readTimeout";

    private final String url;
    private final String baseDn;
    private final String bindDn;
    private final String bindPassword;
    private final String userFilter;
    private final long cacheMillis;
    private final int connectTimeout;
    private final int readTimeout;

    /** 认证通过缓存，key为“用户名\n密码”，value为认证通过的时间戳 */
    private final Map<String, Long> passedCache = new ConcurrentHashMap<>();

    public LdapAuthor(Map<String, String> args) {
        url = args.get(Arg_Url);
        baseDn = args.get(Arg_BaseDn);
        bindDn = args.get(Arg_BindDn);
        bindPassword = args.get(Arg_BindPassword);
        userFilter = StringUtils.defaultIfBlank(args.get(Arg_UserFilter), "(uid={0})");
        cacheMillis = parse(args.get(Arg_CacheMillis), 300000);
        connectTimeout = (int) parse(args.get(Arg_ConnectTimeout), 5000);
        readTimeout = (int) parse(args.get(Arg_ReadTimeout), 5000);

        // 缺配置则启动即失败，避免误以为已受保护实则形同虚设
        Validate.notBlank(url, "Ldap认证器，args.%s不能为空", Arg_Url);
        Validate.notBlank(baseDn, "Ldap认证器，args.%s不能为空", Arg_BaseDn);
        Validate.notBlank(bindDn, "Ldap认证器，args.%s不能为空", Arg_BindDn);
        Validate.notBlank(bindPassword, "Ldap认证器，args.%s不能为空", Arg_BindPassword);
    }

    private static long parse(String val, long dft) {
        if(StringUtils.isBlank(val)) return dft;
        return Long.parseLong(val.trim());
    }

    @Override
    public boolean authenticate(String username, String password) {
        // 空密码在部分ldap实现下会被当作匿名bind从而“认证成功”，必须提前拒绝
        if(StringUtils.isAnyEmpty(username, password)) return false;

        String cacheKey = username+"\n"+password;
        if(cacheMillis>0){
            Long passedAt = passedCache.get(cacheKey);
            if(passedAt!=null && System.currentTimeMillis()-passedAt<cacheMillis) return true;
            if(passedAt!=null) passedCache.remove(cacheKey);
        }

        String userDn;
        try{
            userDn = searchUserDn(username);
        }catch(Exception e){
            // 搜索阶段异常通常是ldap不可达或只读账号配置有误，属于服务端问题，需要暴露出来
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

        if(cacheMillis>0) passedCache.put(cacheKey, System.currentTimeMillis());
        log.info("ldap认证通过：{}", username);
        return true;
    }

    /**
     * 用只读账号bind后搜索用户DN
     * @return 用户DN，搜索不到时返回null
     */
    private String searchUserDn(String username) throws Exception {
        DirContext ctx = ctx(bindDn, bindPassword);
        try{
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setCountLimit(1);
            sc.setReturningAttributes(new String[0]); // 只需要DN，不取任何属性
            // 用占位符传参而非字符串拼接，避免用户名中的特殊字符造成ldap注入
            NamingEnumeration<SearchResult> rs = ctx.search(baseDn, userFilter, new Object[]{username}, sc);
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
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, credentials);
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(connectTimeout));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(readTimeout));
        return new InitialLdapContext(env, null);
    }

}
