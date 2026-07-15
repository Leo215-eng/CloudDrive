package com.disk.user.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.disk.user.domain.entity.UserDO;
import jakarta.validation.constraints.NotNull;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据访问层接口
 * 作用：定义用户表（user）的数据库操作方法，继承BaseMapper<UserDO>获得MyBatis-Plus提供的基础CRUD能力
 * （如insert、update、selectById、page等），同时扩展自定义查询方法
 * @author weikunkun
 */
@Mapper // MyBatis注解，标识这是一个Mapper接口，Spring会扫描并创建该接口的代理实现类
public interface UserMapper extends BaseMapper<UserDO> {

    /**
     * 根据用户ID查询未删除的用户信息
     * @param id 用户主键ID（非空）
     * @return UserDO 用户实体对象（包含用户所有字段），无匹配数据时返回null
     */
    UserDO findById(long id);

    /**
     * 根据用户昵称查询未删除的用户信息
     * @param nickname 用户昵称（参数添加@NotNull校验，避免传入null值）
     * @return UserDO 用户实体对象，无匹配数据时返回null
     */
    UserDO findByNickname(@NotNull String nickname);

    /**
     * 根据用户邮箱（手机号）查询未删除的用户信息
     * 【注：方法名虽为findByEmail，但实际SQL中匹配的是email字段，需确认业务中该字段存储的是邮箱/手机号】
     * @param email 用户邮箱/手机号（参数添加@NotNull校验，避免传入null值）
     * @return UserDO 用户实体对象，无匹配数据时返回null
     */
    UserDO findByEmail(@NotNull String email);
}
