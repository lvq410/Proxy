package com.lvt4j.socketproxy.auth;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 本地token认证器的管理接口。
 * <p>
 * 仅在应用以servlet模式启动时才加载(即配置{@code spring.main.web-application-type: servlet})，
 * 默认的{@code none}模式下整个管理界面不存在，不增加任何攻击面。
 * <p>
 * 接口自身以管理员token做basic认证，管理员token在用户存储首次初始化时生成并打印于日志。
 *
 * @author LV on 2026年8月31日
 */
@RestController
@RequestMapping("/author")
@ConditionalOnWebApplication(type=Type.SERVLET)
public class AuthorAdminController {

    @Autowired
    private AuthorRegistry registry;

    /**
     * 管理界面页面。
     * <p>
     * 页面放在classpath而非static目录下，由本controller显式提供，
     * 使其只存在于主端口({@code server.port})，
     * 而不会随静态资源一并出现在管理端口({@code management.server.port})上。
     */
    @GetMapping(value="/", produces="text/html;charset=UTF-8")
    public String page() throws IOException {
        try(InputStream in = getClass().getResourceAsStream("author.html")){
            return IOUtils.toString(in, UTF_8);
        }
    }

    /**
     * 可管理的认证器id列表。<br>
     * 此接口不做认证：仅暴露认证器id(配置中本就公开的标识)，
     * 不含任何凭据，且管理页需要它来填充选择框。
     */
    @GetMapping("/list")
    public Object authors() {
        List<String> ids = registry.ids();
        ids.removeIf(id->registry.get(id, LocalTokenAuthor.class)==null);
        return ok("authors", ids);
    }

    /** 用户列表 */
    @GetMapping("/{authorId}/users")
    public Object users(@PathVariable String authorId, HttpServletRequest req) {
        ResponseEntity<?> denied = check(req, authorId);
        if(denied!=null) return denied;
        return ok("users", store(authorId).list());
    }

    /** 新增用户，返回生成的token(仅此一次可见) */
    @PostMapping("/{authorId}/users")
    public Object add(@PathVariable String authorId, @RequestBody Map<String, String> body,
            HttpServletRequest req) {
        ResponseEntity<?> denied = check(req, authorId);
        if(denied!=null) return denied;
        try{
            String token = store(authorId).add(body.get("username"), body.get("remark"), System.currentTimeMillis());
            return ok("token", token);
        }catch(IllegalArgumentException e){
            return err(e.getMessage());
        }
    }

    /** 重置用户token，返回新token(仅此一次可见) */
    @PostMapping("/{authorId}/users/{username}/reset")
    public Object reset(@PathVariable String authorId, @PathVariable String username,
            HttpServletRequest req) {
        ResponseEntity<?> denied = check(req, authorId);
        if(denied!=null) return denied;
        try{
            return ok("token", store(authorId).resetToken(username));
        }catch(IllegalArgumentException e){
            return err(e.getMessage());
        }
    }

    /** 启用/停用用户 */
    @PostMapping("/{authorId}/users/{username}/enabled")
    public Object enabled(@PathVariable String authorId, @PathVariable String username,
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<?> denied = check(req, authorId);
        if(denied!=null) return denied;
        try{
            store(authorId).setEnabled(username, Boolean.TRUE.equals(body.get("enabled")));
            return ok();
        }catch(IllegalArgumentException e){
            return err(e.getMessage());
        }
    }

    /** 删除用户 */
    @DeleteMapping("/{authorId}/users/{username}")
    public Object remove(@PathVariable String authorId, @PathVariable String username,
            HttpServletRequest req) {
        ResponseEntity<?> denied = check(req, authorId);
        if(denied!=null) return denied;
        try{
            store(authorId).remove(username);
            return ok();
        }catch(IllegalArgumentException e){
            return err(e.getMessage());
        }
    }

    /** 重置管理员token，返回新token(仅此一次可见) */
    @PostMapping("/{authorId}/admin/reset")
    public Object resetAdmin(@PathVariable String authorId, HttpServletRequest req) {
        ResponseEntity<?> denied = check(req, authorId);
        if(denied!=null) return denied;
        return ok("token", store(authorId).resetAdminToken());
    }

    // ------------------------------------------------------------------

    private UserStore store(String authorId) {
        LocalTokenAuthor author = registry.get(authorId, LocalTokenAuthor.class);
        if(author==null) throw new IllegalArgumentException("本地token认证器不存在:"+authorId);
        return author.store();
    }

    /**
     * 校验管理员凭据
     * @return 校验不通过时返回响应，通过时返回null
     */
    private ResponseEntity<?> check(HttpServletRequest req, String authorId) {
        LocalTokenAuthor author = registry.get(authorId, LocalTokenAuthor.class);
        if(author==null){
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(single("err", "本地token认证器不存在:"+authorId));
        }
        if(author.store().authenticateAdmin(adminToken(req))) return null;
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header("WWW-Authenticate", "Basic realm=\"proxy-admin\"")
            .body(single("err", "管理员认证失败"));
    }

    /** 从basic认证头中取管理员token，用户名部分忽略 */
    private String adminToken(HttpServletRequest req) {
        String authorization = req.getHeader("Authorization");
        if(StringUtils.isBlank(authorization)) return null;
        if(!StringUtils.startsWithIgnoreCase(authorization, "Basic ")) return null;
        String userPass;
        try{
            userPass = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), UTF_8);
        }catch(Exception e){
            return null;
        }
        int idx = userPass.indexOf(':');
        return idx<0? userPass: userPass.substring(idx+1);
    }

    private static Map<String, Object> ok() {
        return single("ok", true);
    }
    private static Map<String, Object> ok(String key, Object val) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put(key, val);
        return m;
    }
    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("err", msg);
        return m;
    }
    private static Map<String, Object> single(String key, Object val) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, val);
        return m;
    }

}
