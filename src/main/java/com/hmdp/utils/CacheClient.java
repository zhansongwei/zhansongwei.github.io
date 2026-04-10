package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr( value), time, unit);
    }

    public void setLogin(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds( time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //reids查询数据
        String Json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //redis有数据
        if(StrUtil.isNotBlank(Json)) {
            return JSONUtil.toBean(Json, type);
        }
        if(Json != null) {
            return null;
        }
        R r = dbFallback.apply(id);
        //如果查不到,尝试获取互斥锁
        if(r == null) {
            stringRedisTemplate.opsForValue().set(keyPrefix + id, "", time, unit);
            return null;
        }
        //缓存到redis
        this.set(keyPrefix + id, r, time, unit);
        return r;
    }

    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,Long id, Class<R> type, Function<ID, R> dbFallback) {
        //逻辑过期解决缓存击穿
        //reids查询数据
        String Json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //如果查不到直接返回null(一定能查到);
        if(StrUtil.isBlank(Json)) {
            try {
                Json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        //判断是否已经逻辑过期
        if(LocalDateTime.now().isBefore(redisData.getExpireTime())) {
            //没有逻辑过期,直接返回数据
            return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        }
        //已经逻辑过期,尝试获取互斥锁
        boolean key = tryLock(LOCK_SHOP_KEY + id);
        if(key) {
            //再次检测redis缓存是否过期 DoubleCheck
            //再次检测 redis 缓存是否过期 DoubleCheck
            String newShopJson = stringRedisTemplate.opsForValue().get(keyPrefix + id);
            RedisData newRedisData = JSONUtil.toBean(newShopJson, RedisData.class);
            if(LocalDateTime.now().isBefore(newRedisData.getExpireTime())){
                //没有过期（说明其他线程已经更新过了）
                return JSONUtil.toBean(JSONUtil.toJsonStr(newRedisData.getData()), type);
            }
            //获取锁成功,开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                //查询数据库
//                Shop shop = (Shop)redisData.getData();
//                Shop newShop = getById(shop.getId());
//                RedisData newRedisData = new RedisData();
//                newRedisData.setData(newShop);
//                redisData.setExpireTime(LocalDateTime.now().plusSeconds(30L));
//                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(newRedisData));
                try {
                } catch (Exception e) {
                    throw new RuntimeException();
                }finally {
                    //释放锁
                    unLock(keyPrefix + id);
                }
            });
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()),  type);
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

}
