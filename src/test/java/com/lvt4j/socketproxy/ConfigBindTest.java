package com.lvt4j.socketproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Collections;

import org.junit.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.convert.support.DefaultConversionService;

import com.lvt4j.socketproxy.Config.PwsConfig;

/**
 * 配置绑定测试。
 * <p>
 * 重点保障标量写法({@code pws: 90})仍能绑定：
 * 历史配置一直是这种写法，若只支持数组写法，升级后pws将不会启动。
 *
 * @author LV on 2026年8月28日
 */
public class ConfigBindTest {

    /** pws单端口标量写法，如 {@code pws: 90} */
    @Test
    public void pws_scalar() {
        Config config = bind("pws", "90");
        assertEquals(1, config.getPws().size());
        PwsConfig c = config.getPws().get(0);
        assertEquals(90, c.getPort().intValue());
        assertFalse("未配置auth时不应开启认证", c.authEnabled());
    }

    /** pws数组写法，如 {@code pws: [80]} */
    @Test
    public void pws_list() {
        Config config = bind("pws[0]", "80");
        assertEquals(1, config.getPws().size());
        assertEquals(80, config.getPws().get(0).getPort().intValue());
    }

    /** pws对象写法，带认证配置 */
    @Test
    public void pws_object_with_auth() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("pws[0].port", "90");
        source.put("pws[0].auth.enabled", "true");
        source.put("pws[0].auth.url", "ldap://127.0.0.1:389");
        source.put("pws[0].auth.baseDn", "ou=people,dc=example,dc=com");
        source.put("pws[0].auth.bindDn", "cn=readonly,dc=example,dc=com");
        source.put("pws[0].auth.bindPassword", "pass");
        Config config = bind(source);

        PwsConfig c = config.getPws().get(0);
        assertEquals(90, c.getPort().intValue());
        assertEquals(true, c.authEnabled());
        assertEquals("ldap://127.0.0.1:389", c.getAuth().getUrl());
        assertEquals("默认的userFilter应生效", "(uid={0})", c.getAuth().getUserFilter());
    }

    /** socks5单端口标量写法，如 {@code socks5: 1080}，线上历史配置即为此写法 */
    @Test
    public void socks5_scalar() {
        Config config = bind("socks5", "1080");
        assertEquals(1, config.getSocks5().size());
        assertEquals(1080, config.getSocks5().get(0).getPort().intValue());
    }

    private Config bind(String key, String value) {
        return bind(new MapConfigurationPropertySource(Collections.singletonMap(key, value)));
    }

    /**
     * 按spring实际行为绑定：注册{@link Config.Socks5ConfigConverter}等转换器，
     * 使纯数字写法能转成对应的配置对象。
     */
    private Config bind(ConfigurationPropertySource source) {
        DefaultConversionService conversion = new DefaultConversionService();
        conversion.addConverter(new Config.Socks5ConfigConverter());
        conversion.addConverter(new Config.PwsConfigConverter());
        return new Binder(Collections.singletonList(source), null, conversion, null)
            .bind("", Config.class).get();
    }

}
