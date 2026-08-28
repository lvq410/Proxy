package com.lvt4j.socketproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.lvt4j.socketproxy.Config.LdapAuthConfig;
import com.lvt4j.socketproxy.Config.PwsConfig;

/**
 * pws代理测试，覆盖开启/未开启LDAP认证两种情形。
 * <p>
 * 借助{@link EmbeddedLdap}在本地起一个内存LDAP服务，
 * 使{@link LdapPwsAuthenticator}的“只读账号搜索用户DN + 用户DN二次bind”流程
 * 能在测试中被真实地走一遍，而非用假认证器绕过。
 *
 * @author LV on 2026年8月28日
 */
public class PwsServiceTest extends BaseTest {

    /** 内存ldap中预置的用户及其密码 */
    private static final String User = "zhangsan";
    private static final String Password = "pass:word-with-colon";

    private EmbeddedLdap ldap;

    private int port;

    private PwsService service;
    private TcpService tcpService;

    private Config config;
    private ChannelReader reader;
    private ChannelWriter writer;
    private ChannelAcceptor acceptor;
    private ChannelConnector connector;
    private ProtocolService protocolService;

    /** pws客户端本地入口，由TcpService提供 */
    private int tcpPort;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    /** 被代理的目标服务 */
    private int targetPort;
    private ServerSocket target;
    private Socket targetAccept;

    @Before
    public void before() throws Exception {
        ldap = new EmbeddedLdap(availablePort());
        ldap.addUser(User, Password);

        port = availablePort();
        targetPort = availablePort();
        target = new ServerSocket(targetPort);

        config = new Config();
        config.setMaxIdleTime(60000);

        reader = new ChannelReader(); invoke(reader, "init");
        writer = new ChannelWriter(); invoke(writer, "init");
        acceptor = new ChannelAcceptor(); invoke(acceptor, "init");
        connector = new ChannelConnector(); invoke(connector, "init");
        protocolService = new ProtocolService();
        FieldUtils.writeField(protocolService, "reader", reader, true);
        FieldUtils.writeField(protocolService, "writer", writer, true);
        FieldUtils.writeField(protocolService, "connector", connector, true);

        service = new PwsService();
        FieldUtils.writeField(service, "config", config, true);
        FieldUtils.writeField(service, "connector", connector, true);
        FieldUtils.writeField(service, "reader", reader, true);
        FieldUtils.writeField(service, "writer", writer, true);
    }

    @After
    public void after() throws IOException {
        if(socket!=null) socket.close();
        if(targetAccept!=null) targetAccept.close();
        if(target!=null) target.close();

        if(tcpService!=null) invoke(tcpService, "destory");
        if(service!=null) invoke(service, "destory");
        if(reader!=null) invoke(reader, "destory");
        if(writer!=null) invoke(writer, "destory");
        if(acceptor!=null) invoke(acceptor, "destory");
        if(connector!=null) invoke(connector, "destory");

        if(ldap!=null) ldap.shutdown();
    }

    // ------------------------------------------------------------------
    // 认证器本身
    // ------------------------------------------------------------------

    /** 正确凭据应认证通过 */
    @Test(timeout=60000)
    public void auth_ok() {
        assertTrue(authenticator().authenticate(User, Password));
    }
    /** 密码错误应被拒 */
    @Test(timeout=60000)
    public void auth_wrong_password() {
        assertFalse(authenticator().authenticate(User, "wrong-password"));
    }
    /** 用户不存在应被拒 */
    @Test(timeout=60000)
    public void auth_unknown_user() {
        assertFalse(authenticator().authenticate("nobody", Password));
    }
    /**
     * 空密码应被拒。<br>
     * ldap协议中空密码的simple bind会被当作匿名bind从而“成功”，
     * 必须由认证器提前拦下，否则等同于无认证。
     */
    @Test(timeout=60000)
    public void auth_empty_password() {
        assertFalse(authenticator().authenticate(User, ""));
    }
    /** ldap不可达时应认证失败，而非放行 */
    @Test(timeout=60000)
    public void auth_ldap_unreachable() throws Exception {
        LdapAuthConfig cfg = ldapConfig();
        cfg.setUrl("ldap://127.0.0.1:"+availablePort()); // 无人监听
        cfg.setConnectTimeout(1000);
        cfg.setReadTimeout(1000);
        assertFalse(new LdapPwsAuthenticator(cfg).authenticate(User, Password));
    }
    /** 认证通过的结果应被缓存：关掉ldap后仍能通过 */
    @Test(timeout=60000)
    public void auth_cache() throws Exception {
        PwsAuthenticator authenticator = authenticator();
        assertTrue(authenticator.authenticate(User, Password));

        ldap.shutdown(); ldap = null;

        assertTrue("认证通过的结果应命中缓存", authenticator.authenticate(User, Password));
    }
    /** 关闭缓存后，ldap不可用则不再放行 */
    @Test(timeout=60000)
    public void auth_cache_disabled() throws Exception {
        LdapAuthConfig cfg = ldapConfig();
        cfg.setCacheMillis(0);
        cfg.setConnectTimeout(1000);
        cfg.setReadTimeout(1000);
        PwsAuthenticator authenticator = new LdapPwsAuthenticator(cfg);
        assertTrue(authenticator.authenticate(User, Password));

        ldap.shutdown(); ldap = null;

        assertFalse("未开缓存时不应放行", authenticator.authenticate(User, Password));
    }

    // ------------------------------------------------------------------
    // pws服务端 + 客户端 端到端
    // ------------------------------------------------------------------

    /** 未开启认证时，不带凭据也应能正常代理，保证向后兼容 */
    @Test(timeout=60000)
    public void noAuth_trans() throws Exception {
        startPws(false);
        startTcpClient(null, null);

        assertTrans();
    }

    /** 开启认证且凭据正确时，应能正常代理 */
    @Test(timeout=60000)
    public void auth_trans() throws Exception {
        startPws(true);
        startTcpClient(User, Password);

        assertTrans();
    }

    /** 开启认证但凭据错误时，连接应被拒绝 */
    @Test(timeout=60000)
    public void auth_reject_wrong_password() throws Exception {
        startPws(true);
        startTcpClient(User, "wrong-password");

        assertNoConnection();
    }

    /** 开启认证但完全不带凭据时，连接应被拒绝 */
    @Test(timeout=60000)
    public void auth_reject_no_credential() throws Exception {
        startPws(true);
        startTcpClient(null, null);

        assertNoConnection();
    }

    // ------------------------------------------------------------------
    // 配置装载
    // ------------------------------------------------------------------

    /** pws配置应兼容纯数字写法，且认证默认不开启 */
    @Test(timeout=60000)
    public void config_compatible_plain_port() {
        config.setPws(Arrays.asList(new PwsConfig(port)));
        PwsConfig c = config.getPws().get(0);
        assertEquals(port, c.getPort().intValue());
        assertFalse("未配置auth时不应开启认证", c.authEnabled());
    }

    /** 端口增删应正确反映到servers */
    @Test(timeout=60000)
    @SuppressWarnings("unchecked")
    public void reload() throws Exception {
        int another = availablePort();

        config.setPws(Arrays.asList(new PwsConfig(port), new PwsConfig(another)));
        invoke(service, "init");
        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(2, servers.size());
        assertTrue(CollectionUtils.isEqualCollection(Arrays.asList(port, another), servers.keySet()));

        config.setPws(Arrays.asList(new PwsConfig(another)));
        invoke(service, "reloadConfig");
        servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(1, servers.size());
        assertTrue(CollectionUtils.isEqualCollection(Arrays.asList(another), servers.keySet()));
    }

    /**
     * 端口不变但认证配置变化时应重建服务，
     * 否则改了认证配置却仍按旧配置放行。
     */
    @Test(timeout=60000)
    public void reload_auth_changed() throws Exception {
        config.setPws(Arrays.asList(pwsConfig(false)));
        invoke(service, "init");
        assertNull_authenticator();

        config.setPws(Arrays.asList(pwsConfig(true)));
        invoke(service, "reloadConfig");
        assertNotNull_authenticator();
    }

    // ------------------------------------------------------------------

    /** 起pws服务端 */
    private void startPws(boolean auth) throws Exception {
        config.setPws(Arrays.asList(pwsConfig(auth)));
        invoke(service, "init");
    }

    /**
     * 起pws客户端。<br>
     * 复用{@link TcpService}的pws代理能力，凭据通过{@code pws://user:pass@host:port}携带，
     * 这样测试走的是真实客户端链路而非自己拼websocket握手。
     */
    private void startTcpClient(String user, String password) throws Exception {
        tcpPort = availablePort();

        Config.TcpConfig tcp = new Config.TcpConfig();
        tcp.setPort(tcpPort);
        tcp.setTarget("127.0.0.1:"+targetPort);
        String credential = user==null? "": urlEncode(user)+":"+urlEncode(password)+"@";
        tcp.setProxy("pws://"+credential+"127.0.0.1:"+port);
        config.setTcp(Arrays.asList(tcp));

        tcpService = new TcpService();
        FieldUtils.writeField(tcpService, "config", config, true);
        FieldUtils.writeField(tcpService, "acceptor", acceptor, true);
        FieldUtils.writeField(tcpService, "connector", connector, true);
        FieldUtils.writeField(tcpService, "reader", reader, true);
        FieldUtils.writeField(tcpService, "writer", writer, true);
        FieldUtils.writeField(tcpService, "protocol", protocolService, true);
        invoke(tcpService, "init");

        socket = new Socket("127.0.0.1", tcpPort);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    /** 断言数据能双向传输 */
    private void assertTrans() throws Exception {
        byte[] data = rand();
        out.write(data);

        targetAccept = target.accept();
        assertBs(data, targetAccept.getInputStream());

        data = rand();
        targetAccept.getOutputStream().write(data);
        assertBs(data, in);
    }

    /** 断言认证未通过：pws服务端不应有连接，目标服务也不应被连上 */
    @SuppressWarnings("unchecked")
    private void assertNoConnection() throws Exception {
        out.write(rand());

        Thread.sleep(2000);

        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        Object serverMeta = servers.get(port);
        List<?> connections = (List<?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals("认证未通过不应建立连接", 0, connections.size());
    }

    private void assertNull_authenticator() throws Exception {
        assertEquals(null, authenticatorOf(port));
    }
    private void assertNotNull_authenticator() throws Exception {
        assertTrue("认证配置变更后应重建为带认证的服务", authenticatorOf(port)!=null);
    }
    @SuppressWarnings("unchecked")
    private Object authenticatorOf(int port) throws Exception {
        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        Object serverMeta = servers.get(port);
        return FieldUtils.readField(serverMeta, "authenticator", true);
    }

    private PwsAuthenticator authenticator() {
        return new LdapPwsAuthenticator(ldapConfig());
    }

    private PwsConfig pwsConfig(boolean auth) {
        PwsConfig c = new PwsConfig(port);
        if(auth){
            LdapAuthConfig authConfig = ldapConfig();
            authConfig.setEnabled(true);
            c.setAuth(authConfig);
        }
        return c;
    }

    private LdapAuthConfig ldapConfig() {
        LdapAuthConfig c = new LdapAuthConfig();
        c.setUrl(ldap.url());
        c.setBaseDn(EmbeddedLdap.BaseDn);
        c.setBindDn(EmbeddedLdap.BindDn);
        c.setBindPassword(EmbeddedLdap.BindPassword);
        c.setUserFilter("(uid={0})");
        return c;
    }

    /** userInfo中的特殊字符需转义，否则URI解析不出来 */
    private static String urlEncode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }
}
