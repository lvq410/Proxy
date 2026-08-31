package com.lvt4j.socketproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.lvt4j.socketproxy.Config.AuthConfig;
import com.lvt4j.socketproxy.Config.AuthorConfig;
import com.lvt4j.socketproxy.Config.PwsAuthConfig;
import com.lvt4j.socketproxy.Config.PwsConfig;
import com.lvt4j.socketproxy.auth.AuthorRegistry;
import com.lvt4j.socketproxy.auth.EmbeddedLdap;
import com.lvt4j.socketproxy.auth.LocalTokenAuthor;

/**
 * pws代理测试，覆盖不认证/本地token认证/ldap认证三种情形。
 * <p>
 * ldap场景借助{@link EmbeddedLdap}在本地起一个内存ldap服务，
 * 使认证器的“只读账号搜索用户DN + 用户DN二次bind”流程能被真实地走一遍。
 *
 * @author LV on 2026年8月31日
 */
public class PwsServiceTest extends BaseTest {

    private static final String LdapUser = "zhangsan";
    private static final String LdapPassword = "pass:word-with-colon";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbeddedLdap ldap;

    private int port;

    private PwsService service;
    private TcpService tcpService;
    private AuthorRegistry registry;

    private Config config;
    private ChannelReader reader;
    private ChannelWriter writer;
    private ChannelAcceptor acceptor;
    private ChannelConnector connector;
    private ProtocolService protocolService;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    /** 被代理的目标服务 */
    private int targetPort;
    private ServerSocket target;
    private Socket targetAccept;

    @Before
    public void before() throws Exception {
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

        registry = new AuthorRegistry();
        FieldUtils.writeField(registry, "config", config, true);

        service = new PwsService();
        FieldUtils.writeField(service, "config", config, true);
        FieldUtils.writeField(service, "authorRegistry", registry, true);
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
        if(registry!=null) invoke(registry, "destory");
        if(reader!=null) invoke(reader, "destory");
        if(writer!=null) invoke(writer, "destory");
        if(acceptor!=null) invoke(acceptor, "destory");
        if(connector!=null) invoke(connector, "destory");

        if(ldap!=null) ldap.shutdown();
    }

    // ------------------------------------------------------------------
    // 端到端
    // ------------------------------------------------------------------

    /** 未引用认证器时，不带凭据也应能正常代理，保证向后兼容 */
    @Test(timeout=60000)
    public void noAuth_trans() throws Exception {
        startPws(null);
        startTcpClient(null, null);

        assertTrans();
    }

    /** 本地token认证：凭据正确时应能正常代理 */
    @Test(timeout=60000)
    public void localToken_trans() throws Exception {
        String token = startPwsWithLocalToken();
        startTcpClient("zhangsan", token);

        assertTrans();
    }

    /** 本地token认证：凭据错误时应拒绝 */
    @Test(timeout=60000)
    public void localToken_reject_wrong() throws Exception {
        startPwsWithLocalToken();
        startTcpClient("zhangsan", "wrong-token");

        assertNoConnection();
    }

    /** 本地token认证：不带凭据时应拒绝 */
    @Test(timeout=60000)
    public void localToken_reject_none() throws Exception {
        startPwsWithLocalToken();
        startTcpClient(null, null);

        assertNoConnection();
    }

    /** 本地token认证：用户被停用后应拒绝 */
    @Test(timeout=60000)
    public void localToken_reject_disabled() throws Exception {
        String token = startPwsWithLocalToken();
        registry.get("local", LocalTokenAuthor.class).store().setEnabled("zhangsan", false);
        startTcpClient("zhangsan", token);

        assertNoConnection();
    }

    /** ldap认证：凭据正确时应能正常代理 */
    @Test(timeout=60000)
    public void ldap_trans() throws Exception {
        startPwsWithLdap();
        startTcpClient(LdapUser, LdapPassword);

        assertTrans();
    }

    /** ldap认证：凭据错误时应拒绝 */
    @Test(timeout=60000)
    public void ldap_reject_wrong() throws Exception {
        startPwsWithLdap();
        startTcpClient(LdapUser, "wrong-password");

        assertNoConnection();
    }

    // ------------------------------------------------------------------
    // 配置装载
    // ------------------------------------------------------------------

    /** pws配置应兼容纯数字写法，且默认不认证 */
    @Test(timeout=60000)
    public void config_compatible_plain_port() {
        config.setPws(Arrays.asList(new PwsConfig(port)));
        PwsConfig c = config.getPws().get(0);
        assertEquals(port, c.getPort().intValue());
        assertNull("未配置auth时不应引用认证器", c.authorId());
    }

    /** 引用不存在的认证器id时应启动失败，而非静默地不认证 */
    @Test(timeout=60000)
    public void unknown_authorId() throws Exception {
        invoke(registry, "init");
        config.setPws(Arrays.asList(pwsConfig("no-such-author")));
        invoke(service, "init");

        // 启动失败，不应有服务在监听
        @SuppressWarnings("unchecked")
        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        assertTrue("认证器不存在时不应启动服务", servers.isEmpty());
    }

    /** 端口增删应正确反映到servers */
    @Test(timeout=60000)
    @SuppressWarnings("unchecked")
    public void reload() throws Exception {
        invoke(registry, "init");
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

    /** 端口不变但认证配置变化时应重建，否则改了认证配置却仍按旧配置放行 */
    @Test(timeout=60000)
    public void reload_auth_changed() throws Exception {
        localTokenAuthor("local");
        invoke(registry, "init");

        config.setPws(Arrays.asList(pwsConfig(null)));
        invoke(service, "init");
        assertNull("未引用认证器时应为null", authorOf(port));

        config.setPws(Arrays.asList(pwsConfig("local")));
        invoke(service, "reloadConfig");
        assertNotNull("引用认证器后应重建为带认证的服务", authorOf(port));
    }

    // ------------------------------------------------------------------

    /** 起pws服务端，authorId为null表示不认证 */
    private void startPws(String authorId) throws Exception {
        invoke(registry, "init");
        config.setPws(Arrays.asList(pwsConfig(authorId)));
        invoke(service, "init");
    }

    /**
     * 起带本地token认证的pws服务端
     * @return 为用户zhangsan生成的token
     */
    private String startPwsWithLocalToken() throws Exception {
        localTokenAuthor("local");
        invoke(registry, "init");
        String token = registry.get("local", LocalTokenAuthor.class).store()
            .add("zhangsan", null, System.currentTimeMillis());
        config.setPws(Arrays.asList(pwsConfig("local")));
        invoke(service, "init");
        return token;
    }

    /** 起带ldap认证的pws服务端 */
    private void startPwsWithLdap() throws Exception {
        ldap = new EmbeddedLdap(availablePort());
        ldap.addUser(LdapUser, LdapPassword);

        Map<String, String> args = new LinkedHashMap<>();
        args.put("url", ldap.url());
        args.put("baseDn", EmbeddedLdap.BaseDn);
        args.put("bindDn", EmbeddedLdap.BindDn);
        args.put("bindPassword", EmbeddedLdap.BindPassword);
        args.put("userFilter", "(uid={0})");
        author("ldap", "Ldap", args);

        invoke(registry, "init");
        config.setPws(Arrays.asList(pwsConfig("ldap")));
        invoke(service, "init");
    }

    /** 声明一个本地token认证器 */
    private void localTokenAuthor(String id) throws Exception {
        File store = new File(tmp.getRoot(), id+"-users.json");
        Map<String, String> args = new LinkedHashMap<>();
        args.put(LocalTokenAuthor.Arg_UserStore, store.getAbsolutePath());
        author(id, "LocalToken", args);
    }

    /** 向配置中追加一个认证器声明 */
    private void author(String id, String type, Map<String, String> args) {
        AuthorConfig c = new AuthorConfig();
        c.setId(id);
        c.setType(type);
        c.setArgs(args);

        AuthConfig auth = new AuthConfig();
        auth.setAuthors(Arrays.asList(c));
        config.setAuth(auth);
    }

    private PwsConfig pwsConfig(String authorId) {
        PwsConfig c = new PwsConfig(port);
        if(authorId!=null){
            PwsAuthConfig auth = new PwsAuthConfig();
            auth.setAuthorId(authorId);
            c.setAuth(auth);
        }
        return c;
    }

    /**
     * 起pws客户端。<br>
     * 复用{@link TcpService}的pws代理能力，凭据通过{@code pws://user:pass@host:port}携带，
     * 这样测试走的是真实客户端链路而非自己拼websocket握手。
     */
    private void startTcpClient(String user, String credential) throws Exception {
        int tcpPort = availablePort();

        Config.TcpConfig tcp = new Config.TcpConfig();
        tcp.setPort(tcpPort);
        tcp.setTarget("127.0.0.1:"+targetPort);
        String userInfo = user==null? "": urlEncode(user)+":"+urlEncode(credential)+"@";
        tcp.setProxy("pws://"+userInfo+"127.0.0.1:"+port);
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

    /** 断言认证未通过：pws服务端不应有连接 */
    @SuppressWarnings("unchecked")
    private void assertNoConnection() throws Exception {
        out.write(rand());

        Thread.sleep(2000);

        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        Object serverMeta = servers.get(port);
        List<?> connections = (List<?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals("认证未通过不应建立连接", 0, connections.size());
    }

    @SuppressWarnings("unchecked")
    private Object authorOf(int port) throws Exception {
        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        Object serverMeta = servers.get(port);
        return FieldUtils.readField(serverMeta, "author", true);
    }

    /** userInfo中的特殊字符需转义，否则URI解析不出来 */
    private static String urlEncode(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
