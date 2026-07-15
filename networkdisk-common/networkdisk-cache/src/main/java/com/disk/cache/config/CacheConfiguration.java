package com.disk.cache.config;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 缓存配置
 *
 * @author weikunkun
 */
@Slf4j
@Configuration
@EnableMethodCache(basePackages = "com.disk")
//告诉 Spring 框架：开启 JetCache 的方法缓存功能，扫描 com.disk 包下所有带 @Cached、@CacheInvalidate 等注解的方法；
//        触发 JetCache 自动注册自己的 AOP 切面（JetCacheInterceptor），给对应方法生成代理对象，让缓存注解真正生效
public class CacheConfiguration {

//    它主要做 3 件事。
//            1. 开启 JetCache 注解缓存 作用：让项目里的 @Cached、@CacheRefresh 生效。
//            2. 创建 RedisTemplate 作用：给项目提供一个可以操作 Redis 的对象，并规定序列化方式,
//                当前项目里更多用的是 StringRedisTemplate，这个自定义 RedisTemplate<String,Object> 暂时没有明显业务调用，但它是给“对象存 Redis”准备的。
//            3. 创建 RedisCacheManager  作用：给 Spring Cache 提供一个“基于 Redis 的缓存管理器”。然后 OSS 分片上传里用
   /**
     * 定制链接和操作Redis的客户端工具
     *
     * @param redisConnectionFactory
     * @return
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        // JSON序列化器：把Java对象转成JSON字符串存进Redis
        Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<Object>(Object.class);
        // 字符串序列化器：key统一用纯字符串，保证Redis里可读
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        // 绑定Redis连接工厂
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        // key用字符串序列化
        redisTemplate.setKeySerializer(stringRedisSerializer);
        // value用JSON序列化
        redisTemplate.setValueSerializer(jackson2JsonRedisSerializer);
        // Hash结构的key也用字符串
        redisTemplate.setHashKeySerializer(stringRedisSerializer);
        // Hash结构的value也用JSON
        redisTemplate.setHashValueSerializer(jackson2JsonRedisSerializer);

        return redisTemplate;
    }

    /**
     * 定制化redis的缓存管理器
     *
     * @param redisConnectionFactory
     * @return
     */
    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        // 配置缓存的序列化规则：key字符串、value JSON
        RedisCacheConfiguration redisCacheConfiguration = RedisCacheConfiguration
                .defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<Object>(Object.class)));

        // 构建Redis缓存管理器
        RedisCacheManager cacheManager = RedisCacheManager
                .builder(RedisCacheWriter.lockingRedisCacheWriter(redisConnectionFactory))
                .cacheDefaults(redisCacheConfiguration)
                .transactionAware()
                .build();

        log.info("the redis cache manager is loaded successfully!");
        return cacheManager;
    }

}
