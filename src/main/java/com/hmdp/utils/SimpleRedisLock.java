package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    }
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private String KEY_PRIFIX = "lock:";
    @Override
    public boolean tryLock(long timeoutSec) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(
                KEY_PRIFIX + name, ID_PREFIX + Thread.currentThread().getId(), timeoutSec, TimeUnit.SECONDS));
    }
    @Override
    public void unLock() {
        //基于lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PRIFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

    }


//    @Override
//    public void unLock() {
//        //判断当前线程的id是否一致
//        String id = Thread.currentThread().getId() + ID_PREFIX;
//        if (id.equals(stringRedisTemplate.opsForValue().get(KEY_PRIFIX + name))) {
//            stringRedisTemplate.delete(KEY_PRIFIX + name);
//        }
//    }
}
