package com.disk.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁切面
 *
 * @author weikunkun
 */
@Aspect//@Aspect：标记这是 AOP 切面类，用于拦截方法；
@Component//@Component：交给 Spring 容器管理；
public class DistributeLockAspect {
//    RedissonClient：Redisson 封装的 Redis 客户端，提供可靠可重入锁 RLock；
    // 注入Redisson客户端，操作Redis分布式锁
    private RedissonClient redissonClient;
    // 构造器注入Redisson
    public DistributeLockAspect(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    private static final Logger LOG = LoggerFactory.getLogger(DistributeLockAspect.class);
    //含义：拦截所有标记了 @DistributeLock 注解的方法，在方法执行前后插入加锁、解锁逻辑。
//    @annotation(...) 切点表达式：匹配带有指定注解的方法
    @Around("@annotation(com.disk.lock.DistributeLock)")
//    ProceedingJoinPoint pjp：AOP 连接点，包含目标方法、入参、注解等全部信息。
    public Object process(ProceedingJoinPoint pjp) throws Exception {
        Object response = null;
        // 1. 从连接点拿到目标Method对象
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        // 2. 获取方法上的 @DistributeLock 注解
        DistributeLock distributeLock = method.getAnnotation(DistributeLock.class);


//        步骤 1：解析锁 Key（两种模式：固定 key / SpEL 动态表达式 key）
        String key = distributeLock.key();
        // 判断：如果注解key等于默认空标识，说明要走SpEL表达式解析
        if (DistributeLockConstant.NONE_KEY.equals(key)) {
            // key和表达式都没配置，直接抛异常，无锁无法执行
            if (DistributeLockConstant.NONE_KEY.equals(distributeLock.keyExpression())) {
                throw new DistributeLockException("no lock key found...");
            }
            // SpEL解析器
            SpelExpressionParser parser = new SpelExpressionParser();
            // 解析注解上的表达式字符串
            Expression expression = parser.parseExpression(distributeLock.keyExpression());
            // SpEL运行上下文，用来存放方法入参变量
            EvaluationContext context = new StandardEvaluationContext();
            // 获取当前方法实际传入的参数值数组
            Object[] args = pjp.getArgs();

            // 获取运行时参数的名称 // 反射获取方法形参名（如 context）
            StandardReflectionParameterNameDiscoverer discoverer
                    = new StandardReflectionParameterNameDiscoverer();
            String[] parameterNames = discoverer.getParameterNames(method);

            // 将参数绑定到context中// 把【参数名-参数值】存入SpEL上下文，表达式里#context才能识别
            if (parameterNames != null) {
                for (int i = 0; i < parameterNames.length; i++) {
                    context.setVariable(parameterNames[i], args[i]);
                }
            }

            // 解析表达式，获取结果 // 执行表达式计算，得到最终动态key，比如 "10001-md5xxxx"
            key = String.valueOf(expression.getValue(context));
        }

//        步骤 2：拼接完整 Redis 锁 Key
        String scene = distributeLock.scene();
        // scene业务前缀 + # + 动态key，最终类似 FILE_CHUNK_UPLOAD#10001-md5xxx
        String lockKey = scene + "#" + key;


//        步骤 3：读取注解配置的等待时间、过期时间
        int expireTime = distributeLock.expireTime();// 锁自动过期时间
        int waitTime = distributeLock.waitTime();// 获取锁最多等待多久
        RLock rLock = redissonClient.getLock(lockKey);
        boolean lockResult = false;

//        步骤 4：分支处理两种加锁逻辑（lock 阻塞 /tryLock 非阻塞限时等待）
//        分支 A：waitTime 使用默认值，无限阻塞拿锁 lock ()
//        rLock.lock()：拿不到锁会无限阻塞等待，直到拿到锁。
        if (waitTime == DistributeLockConstant.DEFAULT_WAIT_TIME) {
            // 过期时间默认：永久持有锁，依赖业务执行完finally释放
            if (expireTime == DistributeLockConstant.DEFAULT_EXPIRE_TIME) {
                LOG.info(String.format("lock for key : %s", lockKey));
                rLock.lock();
            } else {// 指定过期时间，自动续期关闭，超时自动释放
                LOG.info(String.format("lock for key : %s , expire : %s", lockKey, expireTime));
                rLock.lock(expireTime, TimeUnit.MILLISECONDS);
            }
            lockResult = true;
        }
//        分支 B：配置了 waitTime，限时尝试获取锁 tryLock ()
//        tryLock(wait, timeUnit)：最多等待 waitTime 毫秒，拿不到直接返回 false，不阻塞。
        else {
            if (expireTime == DistributeLockConstant.DEFAULT_EXPIRE_TIME) {
                LOG.info(String.format("try lock for key : %s , wait : %s", lockKey, waitTime));
                // 只设置等待时间，锁默认自动续期（看门狗）
                lockResult = rLock.tryLock(waitTime, TimeUnit.MILLISECONDS);
            } else {
                LOG.info(String.format("try lock for key : %s , expire : %s , wait : %s", lockKey, expireTime, waitTime));
                // 同时指定等待时间 + 锁过期时间，无自动续期
                lockResult = rLock.tryLock(waitTime, expireTime, TimeUnit.MILLISECONDS);
            }
        }
//        步骤 5：拿锁失败抛出异常
//        并发争抢锁超时，抛出自定义业务异常，上层接口捕获返回给前端。
        if (!lockResult) {
            LOG.warn(String.format("lock failed for key : %s , expire : %s", lockKey, expireTime));
            throw new DistributeLockException("acquire lock failed... key : " + lockKey);
        }
//        步骤 6：拿到锁，执行目标业务方法 pjp.proceed ()
        try {
            LOG.info(String.format("lock success for key : %s , expire : %s", lockKey, expireTime));
            // 执行被@DistributeLock标记的原始业务方法（chunkUpload分片上传逻辑）
            response = pjp.proceed();
        } catch (Throwable e) {
            throw new Exception(e);
        } finally {
            rLock.unlock();
            // 无论业务成功/异常，最终一定会释放锁，防止死锁
            LOG.info(String.format("unlock for key : %s , expire : %s", lockKey, expireTime));
        }
//        步骤 7：返回业务方法执行结果
        return response;
    }
}
