package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static java.util.Collections.emptyList;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.lvt4j.socketproxy.auth.AuthorType;

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
    public static Runnable changeCallback_auth;

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
    @Getter
    private AuthConfig auth = new AuthConfig();

    public void setAuth(AuthConfig auth) {
        if(auth==null) auth = new AuthConfig();
        Set<String> ids = new HashSet<>();
        for(AuthorConfig c : auth.getAuthors()){
            Validate.notBlank(c.id, "认证器，id不能为空");
            Validate.notNull(c.type, "认证器[%s]，type不能为空且必须为支持的类型:%s",
                c.id, Arrays.toString(AuthorType.values()));
            Validate.isTrue(ids.add(c.id), "认证器id重复:%s", c.id);
        }
        this.auth = auth;
    }

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
            if(changeCallback_auth!=null) changeCallback_auth.run();
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
        /** 连接认证配置，为空时不做认证 */
        public PwsAuthConfig auth;

        /** 兼容纯数字配置格式，如 {@code pws: [80]} */
        public PwsConfig(Integer port) { this.port = port; }

        /** 引用的认证器id，为空表示不认证 */
        public String authorId() { return auth==null? null: auth.authorId; }
    }

    /**
     * pws代理的连接认证配置<br>
     * 客户端通过{@code Authorization: Basic}头携带凭据，服务端交由所引用的认证器校验。
     * <p>
     * 注意：basic认证中凭据仅做base64编码而非加密，
     * 开启认证时应确保pws服务处于TLS之后(即客户端用{@code pwss://}或由反向代理终结TLS)，
     * 否则凭据在链路上是明文的。
     *
     * @author LV on 2026年8月31日
     */
    @Data
    public static class PwsAuthConfig {
        /**
         * 引用{@code auth.authors}中声明的认证器id。<br>
         * 不配置则该端口不做认证。
         */
        public String authorId;
    }

    /**
     * 认证相关配置
     * @author LV on 2026年8月31日
     */
    @Data
    public static class AuthConfig {
        /** 各认证器配置 */
        public List<AuthorConfig> authors = emptyList();

        public void setAuthors(List<AuthorConfig> authors) {
            this.authors = authors==null? emptyList(): authors;
        }
    }

    /**
     * 单个认证器配置
     * @author LV on 2026年8月31日
     */
    @Data
    public static class AuthorConfig {
        /** 认证器id，各认证器的id不能重复，供使用方引用 */
        public String id;
        /** 认证方式 */
        public AuthorType type;
        /** 不同认证方式所需的配置不同 */
        public Map<String, String> args = new LinkedHashMap<>();

        public void setType(String type) {
            this.type = AuthorType.parse(type);
        }

        public void setArgs(Map<String, String> args) {
            this.args = args==null? new LinkedHashMap<>(): args;
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