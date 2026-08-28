package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static java.util.Collections.emptyList;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import com.google.common.net.HostAndPort;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 *
 * @author LV on 2022年3月11日
 */
@RefreshScope
@Configuration
@ConfigurationProperties
public class Config {

    public static Runnable changeCallback_tcp;
    public static Runnable changeCallback_socks5;
    public static Runnable changeCallback_http;
    public static Runnable changeCallback_intranet;
    public static Runnable changeCallback_pws;
    
    @Setter@Getter
    private long maxIdleTime;
    
    @Getter
    private List<TcpConfig> tcp = emptyList();
    @Getter
    private List<Socks5Config> socks5 = emptyList();
    @Getter@Setter
    private List<Integer> http = emptyList();
    @Getter
    private List<IntranetConfig> intranet = emptyList();
    @Getter
    private List<PwsConfig> pws = emptyList();
    
    public void setSocks5(List<Socks5Config> socks5) {
        for(Socks5Config c : socks5){
            Validate.notNull(c.port, "socks5代理，port配置不能为空");
            if(c.udpPortMin!=null || c.udpPortMax!=null){
                Validate.notNull(c.udpPortMin, "socks5代理，udpPortMin/udpPortMax必须同时配置");
                Validate.notNull(c.udpPortMax, "socks5代理，udpPortMin/udpPortMax必须同时配置");
                Validate.isTrue(c.udpPortMin<=c.udpPortMax, "socks5代理，udpPortMin必须<=udpPortMax");
            }
        }
        this.socks5 = socks5;
    }

    public void setTcp(List<TcpConfig> tcp) {
        for(TcpConfig c : tcp){
            Validate.notNull(c.target, "tcp反向代理，target配置不能为空");
            Validate.notNull(0<c.port, "tcp反向代理，port配置不能为空");
            Validate.isTrue(0<c.getPort() && c.getPort()<65535, "tcp反向代理，port配置必须在(0~65535]内");
        }
        this.tcp = tcp;
    }
    
    public void setIntranet(List<IntranetConfig> intranet) {
        for(IntranetConfig c : intranet){
            switch(c.getType()){
            case Entry:
                Validate.notNull(c.port, "intranet entry 服务 port配置不能为空");
                Validate.notNull(c.relay, "intranet entry 服务 relay配置不能为空");
                break;
            case Relay:
                Validate.notNull(c.entry, "intranet entry 服务 entry配置不能为空");
                Validate.notNull(c.target, "intranet entry 服务 entry配置不能为空");
                break;
            }
        }
        this.intranet = intranet;
    }

    public void setPws(List<PwsConfig> pws) {
        for(PwsConfig c : pws){
            Validate.notNull(c.port, "pws代理，port配置不能为空");
            Validate.isTrue(0<c.port && c.port<65535, "pws代理，port配置必须在(0~65535]内");
            if(c.auth!=null && c.auth.enabled) c.auth.validate();
        }
        this.pws = pws;
    }

    @PreDestroy
    private void destory() {
        new Thread(()->{
            try{
                Thread.sleep(1000);
            }catch(Exception ig){}
            if(changeCallback_tcp!=null) changeCallback_tcp.run();
            if(changeCallback_socks5!=null) changeCallback_socks5.run();
            if(changeCallback_http!=null) changeCallback_http.run();
            if(changeCallback_intranet!=null) changeCallback_intranet.run();
            if(changeCallback_pws!=null) changeCallback_pws.run();
        }).start();
    }
    
    /**
     * tcp反向代理配置
     * @author LV on 2022年4月7日
     */
    @Data
    public static class TcpConfig {
        /** 绑定的host，可以为空 */
        public InetAddress host;
        public Integer port;
        public HostAndPort target;
        
        public URI proxy;
        
        public void setTarget(String target) {
            this.target = ProxyApp.validHostPort(target);
            Validate.notNull(this.target, "非法的地址:%s", target);
        }
        
        public void setProxy(String proxy) {
            if(proxy==null) return;
            URI url;
            try{
                url = new URI(proxy);
            }catch(Exception e){
                throw new IllegalArgumentException(String.format("非法的代理格式：%s", proxy));
            }
            Protocol protocol = Protocol.parse(url.getScheme());
            Validate.notNull(protocol, "代理配置中协议不支持：%s", url.getScheme());
            Validate.isTrue(url.getPort()>0, "代理配置中端口号异常：%s", url.getPort());
            switch(protocol){
            case Pws: case Pwss: break;
            default:
                Validate.isTrue(StringUtils.isBlank(url.getPath()), "非法的代理格式：%s", proxy);
                Validate.isTrue(StringUtils.isBlank(url.getQuery()), "非法的代理格式：%s", proxy);
                break;
            }
            this.proxy = url;
        }
        
        public String shortDirection() {
            String direction = String.valueOf(port);
            if(host!=null) direction = format(host)+":"+direction;
            return direction;
        }
        
        public String direction() {
            String direction = String.format("%s->%s", port, target);
            if(host!=null) direction = format(host)+":"+direction;
            if(proxy!=null) direction += " via "+proxy;
            return direction;
        }
    }
    
    /**
     * 内网穿透配置
     * @author LV on 2022年3月28日
     */
    @Data
    public static class IntranetConfig {
        
        public Type type;
        
        /** 入口服务配置，端口默认绑定的地址 */
        public String host;
        /** 入口服务配置，通过本端口接收客户端请求 */
        public Integer port;
        /** 入口服务配置，{@link #port}绑定的地址，为空时取{@link #host} */
        public String entryHost;
        /** 入口服务配置，转发服务通过本端口与入口服务建立连接 */
        public Integer relay;
        /** 入口服务配置，{@link #relay}绑定的地址，为空时取{@link #host} */
        public String relayHost;
        
        /** 转发服务配置，入口服务地址 */
        public HostAndPort entry;
        /** 转发服务配置，目标服务地址 */
        public HostAndPort target;
        
        /** 心跳间隔 */
        public Long heartbeatInterval = TimeUnit.SECONDS.toMillis(10);
        /** 多久没收到心跳时断开连接 */
        public Long heartbeatMissTimeout = TimeUnit.MINUTES.toMillis(1);
        
        public void setEntry(String entry) {
            this.entry = ProxyApp.validHostPort(entry);
            Validate.notNull(this.entry, "非法的地址:%s", entry);
        }
        public void setTarget(String target) {
            this.target = ProxyApp.validHostPort(target);
            Validate.notNull(this.target, "非法的地址:%s", target);
        }
        
        public enum Type {
            /** 入口服务 */
            Entry
            /** 转发服务 */
            ,Relay
            ;
        }
    }

    /**
     * socks5代理配置
     */
    @Data @NoArgsConstructor
    public static class Socks5Config {
        /** 监听端口 */
        public Integer port;
        /** UDP中继对外通告地址，为空时取TCP连接的本地地址 */
        public String udpAdvertiseHost;
        /** UDP中继端口范围下限（含），为空时由系统分配 */
        public Integer udpPortMin;
        /** UDP中继端口范围上限（含），为空时由系统分配 */
        public Integer udpPortMax;

        /** 兼容纯数字配置格式，如 {@code socks5: [1080]} */
        public Socks5Config(Integer port) { this.port = port; }
    }

    /**
     * pws代理配置
     * @author LV on 2026年8月28日
     */
    @Data @NoArgsConstructor
    public static class PwsConfig {
        /** 监听端口 */
        public Integer port;
        /** 连接认证配置，为空或未开启时不做认证 */
        public LdapAuthConfig auth;

        /** 兼容纯数字配置格式，如 {@code pws: [80]} */
        public PwsConfig(Integer port) { this.port = port; }

        /** 是否已开启认证 */
        public boolean authEnabled() { return auth!=null && auth.enabled; }
    }

    /**
     * pws代理的LDAP认证配置<br>
     * 客户端通过{@code Authorization: Basic}头携带凭据，服务端向LDAP校验。
     * <p>
     * 注意：basic认证中凭据仅做base64编码而非加密，
     * 开启认证时应确保pws服务处于TLS之后(即客户端用{@code pwss://}或由反向代理终结TLS)，
     * 否则用户密码在链路上是明文的。
     *
     * @author LV on 2026年8月28日
     */
    @Data
    public static class LdapAuthConfig {
        /** 是否开启认证，默认不开启，保持与旧版本一致的无认证行为 */
        public boolean enabled;
        /** ldap服务地址，如 {@code ldap://ldap.example.com:389} */
        public String url;
        /** 搜索用户的起始节点，如 {@code ou=people,dc=example,dc=com} */
        public String baseDn;
        /** 用于搜索用户的只读账号DN */
        public String bindDn;
        /** 只读账号密码 */
        public String bindPassword;
        /** 搜索用户的过滤器，{@code {0}}会被替换为待认证的用户名 */
        public String userFilter = "(uid={0})";
        /** 认证通过结果的缓存时长(毫秒)，避免每次连接都请求ldap；配置为0则不缓存 */
        public long cacheMillis = TimeUnit.MINUTES.toMillis(5);
        /** ldap连接超时(毫秒) */
        public int connectTimeout = 5000;
        /** ldap读取超时(毫秒) */
        public int readTimeout = 5000;

        /** 开启认证时校验必填项，缺失则启动即失败，避免误以为已受保护实则形同虚设 */
        public void validate() {
            Validate.notBlank(url, "pws代理开启认证时，auth.url不能为空");
            Validate.notBlank(baseDn, "pws代理开启认证时，auth.baseDn不能为空");
            Validate.notBlank(bindDn, "pws代理开启认证时，auth.bindDn不能为空");
            Validate.notBlank(bindPassword, "pws代理开启认证时，auth.bindPassword不能为空");
            Validate.notBlank(userFilter, "pws代理开启认证时，auth.userFilter不能为空");
        }
    }

    /**
     * 兼容socks5的纯数字配置写法，如 {@code socks5: 1080} 或 {@code socks5: [1080]}<br>
     * 仅提供单参构造器时spring并不会自动做标量到对象的转换，必须显式注册转换器，
     * 否则历史的纯数字写法会绑定失败导致服务不启动。
     */
    @Component
    @ConfigurationPropertiesBinding
    public static class Socks5ConfigConverter implements Converter<String, Socks5Config> {
        @Override
        public Socks5Config convert(String source) {
            return new Socks5Config(Integer.valueOf(source.trim()));
        }
    }

    /**
     * 兼容pws的纯数字配置写法，如 {@code pws: 90} 或 {@code pws: [90]}
     * @see Socks5ConfigConverter
     */
    @Component
    @ConfigurationPropertiesBinding
    public static class PwsConfigConverter implements Converter<String, PwsConfig> {
        @Override
        public PwsConfig convert(String source) {
            return new PwsConfig(Integer.valueOf(source.trim()));
        }
    }

}