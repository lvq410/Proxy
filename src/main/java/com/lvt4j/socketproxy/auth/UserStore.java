package com.lvt4j.socketproxy.auth;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地用户凭据存储，以json文件保存。
 * <p>
 * 凭据只保存加盐哈希，不保存明文，因此token一旦生成后无法再从服务端查看，
 * 只能重新生成。
 * <p>
 * 关于哈希算法的选择：此处存的是服务端生成的{@value #TokenBytes}字节随机token，
 * 熵足够高，暴力穷举不可行，因此用加盐SHA-256即可，
 * 无需BCrypt等为低熵的人选密码设计的慢哈希(会显著增加每次连接的开销)。
 *
 * @author LV on 2026年8月31日
 */
@Slf4j
public class UserStore {

    /** 生成token的字节数 */
    private static final int TokenBytes = 32;
    /** 盐的字节数 */
    private static final int SaltBytes = 16;

    private static final ObjectMapper Json = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom Random = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final File file;

    /** 内存中的数据，写操作后同步落盘 */
    private volatile StoreData data = new StoreData();

    public UserStore(String path) {
        this.file = new File(path);
        load();
    }

    /** 从文件加载，文件不存在时初始化 */
    private synchronized void load() {
        if(!file.exists()){
            init();
            return;
        }
        try{
            data = Json.readValue(file, StoreData.class);
            if(data.users==null) data.users = new ArrayList<>();
        }catch(Exception e){
            // 文件损坏时不能静默重置，否则会丢失所有用户且悄无声息地变为不可认证
            throw new IllegalStateException("用户存储文件解析失败:"+file.getAbsolutePath(), e);
        }
    }

    /**
     * 首次初始化：生成一个管理员token并打印到日志。<br>
     * 打印明文是为了让首次部署能进入管理界面，拿到后应尽快在界面中重置。
     */
    private void init() {
        String token = generateToken();
        StoreData d = new StoreData();
        d.admin = credential(token);
        data = d;
        save();
        log.warn("=================================================================");
        log.warn(" 已初始化用户存储:{}", file.getAbsolutePath());
        log.warn(" 管理员token(仅本次打印,请立即保存并在管理界面重置):");
        log.warn("   {}", token);
        log.warn("=================================================================");
    }

    /** 落盘，先写临时文件再原子替换，避免写一半进程退出导致文件损坏 */
    private synchronized void save() {
        try{
            File dir = file.getAbsoluteFile().getParentFile();
            if(dir!=null && !dir.exists()) dir.mkdirs();
            File tmp = new File(file.getAbsolutePath()+".tmp");
            Json.writeValue(tmp, data);
            Files.move(tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }catch(IOException e){
            throw new IllegalStateException("用户存储文件保存失败:"+file.getAbsolutePath(), e);
        }
    }

    // ------------------------------------------------------------------
    // 认证
    // ------------------------------------------------------------------

    /** 校验普通用户凭据 */
    public boolean authenticate(String username, String token) {
        if(StringUtils.isAnyEmpty(username, token)) return false;
        User user = data.users.stream().filter(u->username.equals(u.username)).findFirst().orElse(null);
        if(user==null) return false;
        if(!user.enabled) return false;
        return matches(token, user.credential);
    }

    /** 校验管理员凭据 */
    public boolean authenticateAdmin(String token) {
        if(StringUtils.isEmpty(token)) return false;
        return matches(token, data.admin);
    }

    // ------------------------------------------------------------------
    // 管理
    // ------------------------------------------------------------------

    /** 用户列表，不含凭据 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rtn = new ArrayList<>();
        for(User u : data.users){
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.username);
            m.put("remark", u.remark);
            m.put("enabled", u.enabled);
            m.put("createdAt", u.createdAt);
            rtn.add(m);
        }
        return rtn;
    }

    /**
     * 新增用户并生成token
     * @return 生成的token明文，仅此一次可见
     */
    public synchronized String add(String username, String remark, long now) {
        if(StringUtils.isBlank(username)) throw new IllegalArgumentException("用户名不能为空");
        if(exists(username)) throw new IllegalArgumentException("用户已存在:"+username);
        String token = generateToken();
        User u = new User();
        u.username = username.trim();
        u.remark = remark;
        u.enabled = true;
        u.createdAt = now;
        u.credential = credential(token);
        data.users.add(u);
        save();
        return token;
    }

    /**
     * 重置用户token
     * @return 新token明文，仅此一次可见
     */
    public synchronized String resetToken(String username) {
        User u = require(username);
        String token = generateToken();
        u.credential = credential(token);
        save();
        return token;
    }

    /** 启用/停用用户，停用后立即无法认证 */
    public synchronized void setEnabled(String username, boolean enabled) {
        require(username).enabled = enabled;
        save();
    }

    /** 删除用户 */
    public synchronized void remove(String username) {
        User u = require(username);
        data.users.remove(u);
        save();
    }

    /**
     * 重置管理员token
     * @return 新token明文，仅此一次可见
     */
    public synchronized String resetAdminToken() {
        String token = generateToken();
        data.admin = credential(token);
        save();
        return token;
    }

    private boolean exists(String username) {
        return data.users.stream().anyMatch(u->u.username.equals(username.trim()));
    }
    private User require(String username) {
        return data.users.stream().filter(u->u.username.equals(username)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("用户不存在:"+username));
    }

    // ------------------------------------------------------------------
    // 哈希
    // ------------------------------------------------------------------

    /** 生成随机token */
    static String generateToken() {
        byte[] bs = new byte[TokenBytes];
        Random.nextBytes(bs);
        return B64.encodeToString(bs);
    }

    /** 计算凭据，格式为 {@code salt:hash}，均为base64 */
    static String credential(String token) {
        byte[] salt = new byte[SaltBytes];
        Random.nextBytes(salt);
        return B64.encodeToString(salt)+":"+hash(token, salt);
    }

    /** 比对token与已保存的凭据 */
    static boolean matches(String token, String credential) {
        if(StringUtils.isAnyEmpty(token, credential)) return false;
        int idx = credential.indexOf(':');
        if(idx<0) return false;
        byte[] salt;
        try{
            salt = Base64.getUrlDecoder().decode(credential.substring(0, idx));
        }catch(Exception e){
            return false;
        }
        // 定长比对，避免因提前返回而泄露匹配位置
        return MessageDigest.isEqual(
            hash(token, salt).getBytes(StandardCharsets.UTF_8),
            credential.substring(idx+1).getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String token, byte[] salt) {
        try{
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(token.getBytes(StandardCharsets.UTF_8));
            return B64.encodeToString(md.digest());
        }catch(Exception e){
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------

    @Data
    @JsonIgnoreProperties(ignoreUnknown=true)
    public static class StoreData {
        /** 管理员凭据 */
        public String admin;
        public List<User> users = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown=true)
    public static class User {
        public String username;
        /** 加盐哈希后的凭据，格式 salt:hash */
        public String credential;
        public String remark;
        public boolean enabled = true;
        /** 创建时间戳 */
        public long createdAt;
    }

}
