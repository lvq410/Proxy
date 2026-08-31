package com.lvt4j.socketproxy.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 本地token认证器测试
 * @author LV on 2026年8月31日
 */
public class LocalTokenAuthorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File store;

    @Before
    public void before() throws Exception {
        store = new File(tmp.getRoot(), "users.json");
    }

    private LocalTokenAuthor author() {
        return new LocalTokenAuthor(Collections.singletonMap(
            LocalTokenAuthor.Arg_UserStore, store.getAbsolutePath()));
    }

    /** 首次使用应自动初始化存储文件 */
    @Test
    public void init() {
        assertFalse(store.exists());
        author();
        assertTrue("应自动创建存储文件", store.exists());
    }

    /** 存储文件中不应出现token明文 */
    @Test
    public void no_plaintext_in_store() throws Exception {
        UserStore s = author().store();
        String token = s.add("zhangsan", "测试", 1L);
        String content = new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8);
        assertFalse("存储文件中不应出现token明文", content.contains(token));
    }

    /** 正确token应认证通过 */
    @Test
    public void auth_ok() {
        LocalTokenAuthor a = author();
        String token = a.store().add("zhangsan", null, 1L);
        assertTrue(a.authenticate("zhangsan", token));
    }

    /** 错误token应被拒 */
    @Test
    public void auth_wrong_token() {
        LocalTokenAuthor a = author();
        a.store().add("zhangsan", null, 1L);
        assertFalse(a.authenticate("zhangsan", "wrong-token"));
    }

    /** 用户不存在应被拒 */
    @Test
    public void auth_unknown_user() {
        LocalTokenAuthor a = author();
        String token = a.store().add("zhangsan", null, 1L);
        assertFalse(a.authenticate("lisi", token));
    }

    /** 空凭据应被拒 */
    @Test
    public void auth_empty() {
        LocalTokenAuthor a = author();
        a.store().add("zhangsan", null, 1L);
        assertFalse(a.authenticate("zhangsan", ""));
        assertFalse(a.authenticate("", "whatever"));
    }

    /** 一个用户的token不能用于另一个用户 */
    @Test
    public void auth_token_not_shared() {
        LocalTokenAuthor a = author();
        String t1 = a.store().add("zhangsan", null, 1L);
        a.store().add("lisi", null, 1L);
        assertFalse("zhangsan的token不应能认证lisi", a.authenticate("lisi", t1));
    }

    /** 停用后应立即无法认证，启用后恢复 */
    @Test
    public void enabled() {
        LocalTokenAuthor a = author();
        String token = a.store().add("zhangsan", null, 1L);

        a.store().setEnabled("zhangsan", false);
        assertFalse("停用后不应能认证", a.authenticate("zhangsan", token));

        a.store().setEnabled("zhangsan", true);
        assertTrue("启用后应恢复", a.authenticate("zhangsan", token));
    }

    /** 删除后应无法认证 */
    @Test
    public void remove() {
        LocalTokenAuthor a = author();
        String token = a.store().add("zhangsan", null, 1L);
        a.store().remove("zhangsan");
        assertFalse(a.authenticate("zhangsan", token));
    }

    /** 重置后旧token失效、新token生效 */
    @Test
    public void reset() {
        LocalTokenAuthor a = author();
        String old = a.store().add("zhangsan", null, 1L);
        String neo = a.store().resetToken("zhangsan");

        assertNotEquals(old, neo);
        assertFalse("旧token应失效", a.authenticate("zhangsan", old));
        assertTrue("新token应生效", a.authenticate("zhangsan", neo));
    }

    /** 重复用户名应报错 */
    @Test(expected=IllegalArgumentException.class)
    public void add_duplicate() {
        LocalTokenAuthor a = author();
        a.store().add("zhangsan", null, 1L);
        a.store().add("zhangsan", null, 1L);
    }

    /** 数据应持久化，重新加载后仍能认证 */
    @Test
    public void persist() {
        String token = author().store().add("zhangsan", "备注", 1L);

        // 重新构造，模拟重启
        LocalTokenAuthor reloaded = author();
        assertTrue("重启后应仍能认证", reloaded.authenticate("zhangsan", token));

        List<Map<String, Object>> users = reloaded.store().list();
        assertEquals(1, users.size());
        assertEquals("zhangsan", users.get(0).get("username"));
        assertEquals("备注", users.get(0).get("remark"));
    }

    /** 用户列表不应泄露凭据 */
    @Test
    public void list_no_credential() {
        LocalTokenAuthor a = author();
        a.store().add("zhangsan", null, 1L);
        Map<String, Object> u = a.store().list().get(0);
        assertFalse("列表不应包含凭据字段", u.containsKey("credential"));
    }

    /** 管理员token：初始化时生成，重置后旧的失效 */
    @Test
    public void admin() {
        LocalTokenAuthor a = author();
        assertFalse(a.store().authenticateAdmin("not-the-admin-token"));

        String neo = a.store().resetAdminToken();
        assertTrue(a.store().authenticateAdmin(neo));
        assertFalse(a.store().authenticateAdmin("wrong"));
    }

    /** 管理员token不能当作普通用户凭据使用 */
    @Test
    public void admin_not_a_user() {
        LocalTokenAuthor a = author();
        String admin = a.store().resetAdminToken();
        a.store().add("zhangsan", null, 1L);
        assertFalse(a.authenticate("zhangsan", admin));
    }

    /** 每次生成的token与盐都应不同 */
    @Test
    public void token_randomness() {
        LocalTokenAuthor a = author();
        String t1 = a.store().add("u1", null, 1L);
        String t2 = a.store().add("u2", null, 1L);
        assertNotEquals(t1, t2);
        // 相同token两次计算的凭据也应不同(盐不同)
        assertNotEquals(UserStore.credential(t1), UserStore.credential(t1));
    }

    /** 存储文件损坏时应报错而非静默重置 */
    @Test(expected=IllegalStateException.class)
    public void corrupted_store() throws Exception {
        Files.write(store.toPath(), "{ this is not valid json".getBytes(StandardCharsets.UTF_8));
        author();
    }

}
