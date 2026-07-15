package com.disk.user.domain.service;

import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.disk.api.files.request.UserFileQueryRequest;
import com.disk.api.files.response.UserFileQueryResponse;
import com.disk.api.files.response.data.UserFileData;
import com.disk.api.files.service.UserFileFacadeService;
import com.disk.api.user.request.UserRegisterRequest;
import com.disk.api.user.service.UserFacadeService;
import com.disk.base.enums.DeleteEnum;
import com.disk.base.utils.EmptyUtil;
import com.disk.base.utils.IdUtil;
import com.disk.base.utils.PasswordUtil;
import com.disk.user.controller.UserController;
import com.disk.user.domain.entity.UserDO;
import com.disk.user.domain.entity.convertor.UserConvertor;
import com.disk.user.domain.response.UserInfoVO;
import com.disk.user.infrastructure.exception.UserException;
import com.disk.user.infrastructure.mapper.UserMapper;
import com.disk.user.infrastructure.util.UserConstants;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static com.disk.user.infrastructure.exception.UserErrorCode.DUPLICATE_TELEPHONE_NUMBER;
import static com.disk.user.infrastructure.exception.UserErrorCode.USER_INFO_FAIL;
import static com.disk.user.infrastructure.exception.UserErrorCode.USER_NOT_EXIST;
import static com.disk.user.infrastructure.exception.UserErrorCode.USER_OPERATE_FAILED;

/**
 * 类描述: 用户服务
 *
 * @author weikunkun
 */
@Service
public class UserService extends ServiceImpl<UserMapper, UserDO> implements InitializingBean {

    @DubboReference(version = "1.0.0")
    private UserFileFacadeService userFileFacadeService;


    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private UserMapper userMapper;

    private RBloomFilter<String> nickNameBloomFilter;

    private RBloomFilter<String> inviteCodeBloomFilter;

    private RScoredSortedSet<String> inviteRank;


    /**
     * TODO 注册、认证、激活、冻结、解禁
     * @throws Exception
     */

    /**
     * 根据id查询用户信息
     *
     * @param email
     * @return
     */
    @Cached(name = "user:cached:telephone:", expire = 3000, cacheType = CacheType.BOTH, key = "#telephone", cacheNullValue = true)
    @CacheRefresh(refresh = 60, timeUnit = TimeUnit.HOURS)
    public UserDO findByEmail(String email) {
       return userMapper.findByEmail(email);
    }


    /**
     * 根据id查询用户信息
     *
     * @param userId
     * @return
     */
    @Cached(name = "user:cached:id:", expire = 3000, cacheType = CacheType.BOTH, key = "#userId", cacheNullValue = true)
    @CacheRefresh(refresh = 60, timeUnit = TimeUnit.HOURS)
    public UserDO findById(Long userId) {
        UserDO userDO = userMapper.findById(userId);
        return userDO;
    }


    public boolean nickNameExist(String nickName) {
        //如果布隆过滤器中存在，再进行数据库二次判断
        if (this.nickNameBloomFilter != null && this.nickNameBloomFilter.contains(nickName)) {
            return userMapper.findByNickname(nickName) != null;
        }

        return false;
    }

    public UserDO register(UserRegisterRequest request) {
        if (request == null || StringUtils.isBlank(request.getEmail()) || StringUtils.isBlank(request.getPassword())) {
            throw new UserException(USER_OPERATE_FAILED);
        }
        if (userMapper.findByEmail(request.getEmail()) != null) {
            throw new UserException(DUPLICATE_TELEPHONE_NUMBER);
        }

        String nickName = StringUtils.isBlank(request.getNickname())
                ? StringUtils.defaultIfBlank(StringUtils.substringBefore(request.getEmail(), "@"), request.getEmail())
                : request.getNickname();

        UserDO insertDO = new UserDO();
        insertDO.setEmail(request.getEmail());
        insertDO.setNickName(nickName);
        //生成唯一用户ID（雪花算法/Snowflake）
        //IdUtil.get() 生成一个全局唯一的数字ID，作为用户主键，不依赖数据库自增，适合分布式系统
        insertDO.setId(IdUtil.get());
        //密码不能明文存储，用工具类加密后再存
        //把用户输入的明文密码加密成哈希值（如 MD5/SHA/Bcrypt），存入数据库
        insertDO.setPasswordHash(PasswordUtil.encryptPassword(request.getPassword()));
        //置已用存储空间 = 0（新用户还没存任何文件）
        //0L 表示 long 类型的 0
        insertDO.setUseSpace(0L);
        //设置总可用存储空间 = 初始配额（比如 100MB）
        //从常量类里读取，方便统一修改
        insertDO.setTotalSpace(UserConstants.USER_INIT_SPACE);
        //设置逻辑删除标记 = 未删除（0 或 false）
        //不会真正删数据，只是打标记，方便数据恢复
        insertDO.setDeleted(DeleteEnum.NO.getCode());
        // 记录用户注册时间（或最后一次登录时间）
        //new Date() 获取当前系统时间
        insertDO.setLastLoginTime(new Date());
        //设置默认头像（一个 GitHub 的公共头像地址）
        //新用户注册时给个默认头像，后续可以自己修改
        insertDO.setProfilePhotoUrl("https://avatars.githubusercontent.com/u/25891014?v=4");
        userMapper.insert(insertDO);
        return insertDO;
    }

    /**
     * 注册
     *
     * @param email
     * @param nickName
     * @param password
     * @return
     */
    private UserDO doRegister(String email, String nickName, String password) {
        if (userMapper.findByEmail(email) != null) {
            throw new UserException(DUPLICATE_TELEPHONE_NUMBER);
        }

        UserDO user = new UserDO();
        user.register(email, nickName, password);
        return save(user) ? user : null;
    }

    private boolean addNickName(String nickName) {
        return this.nickNameBloomFilter != null && this.nickNameBloomFilter.add(nickName);
    }

    private boolean addInviteCode(String inviteCode) {
        return this.inviteCodeBloomFilter != null && this.inviteCodeBloomFilter.add(inviteCode);
    }

    private void updateInviteRank(String inviterId) {
        if (inviterId == null) {
            return;
        }
        RLock rLock = redissonClient.getLock(inviterId);
        rLock.lock();
        try {
            Double score = inviteRank.getScore(inviterId);
            if (score == null) {
                score = 0.0;
            }
            inviteRank.add(score + 100.0, inviterId);
        } finally {
            rLock.unlock();
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.nickNameBloomFilter = redissonClient.getBloomFilter("nickName");
        if (nickNameBloomFilter != null && !nickNameBloomFilter.isExists()) {
            this.nickNameBloomFilter.tryInit(100000L, 0.01);
        }

        this.inviteCodeBloomFilter = redissonClient.getBloomFilter("inviteCode");
        if (inviteCodeBloomFilter != null && !inviteCodeBloomFilter.isExists()) {
            this.inviteCodeBloomFilter.tryInit(100000L, 0.01);
        }

        this.inviteRank = redissonClient.getScoredSortedSet("inviteRank");
    }

    /**
     * 根据用户ID组装前端展示用的用户完整信息VO
     * @param userId 当前登录用户ID
     * @return 包含用户基础信息+网盘根目录信息的VO
     */
    public UserInfoVO getUserInfo(Long userId) {
//        this.findById(userId) 里的 this 代表当前这个 UserService 实例对象，
//        findById() 是本类自己的方法，所以 this.方法名() 就是调用当前类自身的成员方法。
        // 1. 根据用户ID查询数据库用户实体
        UserDO userDO = this.findById(userId);
        // 判断用户记录不存在，抛出用户不存在业务异常
        if (EmptyUtil.isEmpty(userDO)) {
            throw new UserException(USER_NOT_EXIST);
        }

        // 2. 构建文件查询请求，Dubbo远程调用文件微服务
        UserFileQueryRequest userFileQueryRequest = new UserFileQueryRequest(userId);
        // 远程调用文件Facade，查询该用户的网盘根目录信息
        UserFileQueryResponse<UserFileData> userFileInfo = userFileFacadeService.getUserFileInfo(userFileQueryRequest);
        // 取出远程返回的根目录数据
        UserFileData data = userFileInfo.getData();

        // 3. 根目录数据为空，抛出获取用户信息失败异常
        if (EmptyUtil.isEmpty(data)) {
            throw new UserException(USER_INFO_FAIL);
        }

        // 4. 组装对外展示VO，整合用户表+文件根目录数据
        UserInfoVO userInfoVO = new UserInfoVO();
        userInfoVO.setNickname(userDO.getNickName());         // 用户昵称
        userInfoVO.setRootFileId(data.getId());               // 网盘根目录ID
        userInfoVO.setRootFilename(data.getFilename());       // 根目录名称
        userInfoVO.setUserId(data.getUserId());               // 用户ID
        userInfoVO.setProfilePhotoUrl(userDO.getProfilePhotoUrl()); // 用户头像

        return userInfoVO;
    }

}
