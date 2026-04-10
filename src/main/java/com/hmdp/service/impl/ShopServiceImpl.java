package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //使用工具类方法防止缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if(shop == null) {
//            return Result.fail("店铺不存在");
//        }
        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        return Result.ok(shop);
    }

    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        //逻辑过期解决缓存击穿
        //reids查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //如果查不到直接返回null(一定能查到);
        if(StrUtil.isBlank(shopJson)) {
            try {
                addShopToRedis(id, 30L);
                shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //判断是否已经逻辑过期
        if(LocalDateTime.now().isBefore(redisData.getExpireTime())) {
            //没有逻辑过期,直接返回数据
            return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), Shop.class);
        }
        //已经逻辑过期,尝试获取互斥锁
        boolean key = tryLock(LOCK_SHOP_KEY + id);
        if(key) {
            //再次检测redis缓存是否过期 DoubleCheck
            //再次检测 redis 缓存是否过期 DoubleCheck
            String newShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            RedisData newRedisData = JSONUtil.toBean(newShopJson, RedisData.class);
            if(LocalDateTime.now().isBefore(newRedisData.getExpireTime())){
                //没有过期（说明其他线程已经更新过了）
                return JSONUtil.toBean(JSONUtil.toJsonStr(newRedisData.getData()), Shop.class);
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
                    addShopToRedis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException();
                }finally {
                    //释放锁
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), Shop.class);
    }
    //获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    //防止缓存穿透
    public Shop queryWithPassThrough(Long id) {
        //reids查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //redis有数据
        if(StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson != null) {
            return null;
        }
        Shop shop = getById(id);
        //如果redis查不到,尝试获取互斥锁
        if(shop == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //缓存到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        //reids查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //redis有数据
        if(StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson != null) {
            return null;
        }
        Shop shop = null;
        //如果redis查不到,尝试获取互斥锁
        try {
            boolean key = tryLock(LOCK_SHOP_KEY + id);
            if (!key) {
                //获取锁失败,则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取锁成功,再次检测缓存是否存在
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            //查询数据库
            shop = getById(id);
            //模拟远程数据库的延迟
            Thread.sleep(200);
            if (shop == null) {
                //数据库中不存在数据
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库中存在数据
            //缓存到redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unLock(LOCK_SHOP_KEY + id);
        }
        return shop;
    }

    //数据预热，向redis里缓存热点数据,设置逻辑过期时间
    public void addShopToRedis(Long id, Long expireSeconds) throws Exception {
        Shop shop = getById(id);
        //缓存延迟
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
