package com.lvt4j.socketproxy.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.lvt4j.socketproxy.Config;
import com.lvt4j.socketproxy.Config.AuthorConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * 认证器注册表，按配置{@code auth.authors}构建认证器，供使用方按id引用。
 *
 * @author LV on 2026年8月31日
 */
@Slf4j
@Service
public class AuthorRegistry {

    @Autowired
    private Config config;

    /** id -> 认证器 */
    private volatile Map<String, Author> authors = Collections.emptyMap();
    /** id -> 构建该认证器时的配置，用于判断配置是否变化 */
    private volatile Map<String, AuthorConfig> configs = Collections.emptyMap();

    @PostConstruct
    private void init() {
        Config.changeCallback_auth = this::reloadConfig;
        reloadConfig();
    }

    public synchronized void reloadConfig() {
        Map<String, Author> newAuthors = new LinkedHashMap<>();
        Map<String, AuthorConfig> newConfigs = new LinkedHashMap<>();
        for(AuthorConfig c : config.getAuth().getAuthors()){
            // 配置未变则复用已有实例，避免重建时丢失缓存、重新加载文件
            Author exist = authors.get(c.getId());
            if(exist!=null && Objects.equals(configs.get(c.getId()), c)){
                newAuthors.put(c.getId(), exist);
                newConfigs.put(c.getId(), c);
                continue;
            }
            try{
                newAuthors.put(c.getId(), c.getType().build(c.getArgs()));
                newConfigs.put(c.getId(), c);
                log.info("认证器[{}]({})已加载", c.getId(), c.getType());
            }catch(Exception e){
                log.error("认证器[{}]({})加载失败", c.getId(), c.getType(), e);
            }
        }
        // 被移除或重建的认证器释放资源
        Map<String, Author> old = authors;
        authors = newAuthors;
        configs = newConfigs;
        old.forEach((id, author)->{
            if(newAuthors.get(id)==author) return;
            try{ author.destory(); }catch(Exception ig){}
        });
    }

    @PreDestroy
    private synchronized void destory() {
        Config.changeCallback_auth = null;
        authors.values().forEach(a->{
            try{ a.destory(); }catch(Exception ig){}
        });
        authors = Collections.emptyMap();
        configs = Collections.emptyMap();
    }

    /**
     * 按id取认证器
     * @return 不存在时返回null
     */
    public Author get(String id) {
        return authors.get(id);
    }

    /**
     * 按id取认证器，取不到则抛异常。<br>
     * 用于使用方启动时校验：认证器id写错时应当启动失败，
     * 而非静默地变成不认证。
     */
    public Author require(String id) {
        Author author = authors.get(id);
        Validate.notNull(author, "认证器不存在:%s", id);
        return author;
    }

    /** 取指定类型的认证器 */
    @SuppressWarnings("unchecked")
    public <T extends Author> T get(String id, Class<T> type) {
        Author author = authors.get(id);
        if(author==null || !type.isInstance(author)) return null;
        return (T) author;
    }

    /** 所有认证器id */
    public List<String> ids() {
        return new ArrayList<>(authors.keySet());
    }

}
