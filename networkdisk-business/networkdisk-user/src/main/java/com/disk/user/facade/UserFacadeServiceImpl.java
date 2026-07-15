package com.disk.user.facade;

import com.disk.api.user.request.UserActiveRequest;
import com.disk.api.user.request.UserAuthRequest;
import com.disk.api.user.request.UserModifyRequest;
import com.disk.api.user.request.UserQueryRequest;
import com.disk.api.user.request.UserRegisterRequest;
import com.disk.api.user.request.condition.UserIdQueryCondition;
import com.disk.api.user.request.condition.UserEmailQueryCondition;
import com.disk.api.user.response.UserOperatorResponse;
import com.disk.api.user.response.UserQueryResponse;
import com.disk.api.user.response.data.UserInfo;
import com.disk.api.user.service.UserFacadeService;
import com.disk.rpc.facade.Facade;
import com.disk.user.domain.entity.UserDO;
import com.disk.user.domain.entity.convertor.UserConvertor;
import com.disk.user.domain.service.UserService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@DubboService(version = "1.0.0")
public class UserFacadeServiceImpl implements UserFacadeService {

    @Autowired
    private UserService userService;

    /**
     * 用户查询统一门面接口实现
     * 核心逻辑：根据传入的不同查询条件，分发至对应数据库查询方法，再将数据库DO转换为前端VO并封装统一返回体
     * 支持两种查询维度：用户ID查询 / 用户邮箱查询
     * 不支持的查询条件类型会直接抛出不支持操作异常
     * @param request 用户查询请求入参，内部封装多态查询条件
     * @return UserQueryResponse 统一用户查询返回体，内部封装UserInfo前端展示对象
     */
    @Facade
    @Override
    public UserQueryResponse<UserInfo> query(UserQueryRequest request) {
//        request 是传进来的 UserQueryRequest 对象，里面有个字段：

        //private UserQueryCondition userQueryCondition;
        //     如果你调用 new UserQueryRequest(1001L) → 里面存的是 UserIdQueryCondition 对象（只装 userId）
        //    如果你调用 new UserQueryRequest("test@123.com") → 里面存的是 UserEmailQueryCondition 对象（只装 email）
        //request.getUserQueryCondition() 就是把这个条件对象取出来交给 switch 判断。
        UserDO userDO = switch (request.getUserQueryCondition()) {
//            判断：刚才拿到的条件对象 是不是 UserIdQueryCondition（按 ID 查询的条件）
//如果是：自动把这个对象强制转成 UserIdQueryCondition，起个名字叫 userIdQueryCondition
//            yield：把查到的 UserDO 返回出去，整个 switch 表达式的值就是这个 UserDO
            // 查询条件为用户ID，取出ID调用service按主键查用户库数据
            case UserIdQueryCondition userIdQueryCondition:
                yield userService.findById(userIdQueryCondition.getUserId());
                // 查询条件为邮箱，取出邮箱调用service按唯一邮箱查询用户
            case UserEmailQueryCondition userEmailQueryCondition:
                yield userService.findByEmail(userEmailQueryCondition.getEmail());
                // 传入未知查询条件类型，抛出不支持操作异常，阻断非法参数
            default:
                throw new UnsupportedOperationException(request.getUserQueryCondition() + "'' is not supported");
        };

        // 创建微服务统一响应包装对象
        UserQueryResponse<UserInfo> response = new UserQueryResponse();
        // MapStruct工具：数据库DO实体转对外传输DTO，过滤密码等敏感字段
        UserInfo userInfoVO = UserConvertor.INSTANCE.mapToVo(userDO);
        // 标记本次RPC调用执行成功
        response.setSuccess(true);
        // 将转换后的用户信息放入响应data载体
        response.setData(userInfoVO);
        // 返回标准结构给Dubbo调用方（auth服务）
        return response;
    }



    @Facade
    @Override
    public UserOperatorResponse<UserInfo> register(UserRegisterRequest request) {
        UserDO register = userService.register(request);
        UserOperatorResponse<UserInfo> response = new UserOperatorResponse<>();
        UserInfo userInfoVO = UserConvertor.INSTANCE.mapToVo(register);
        response.setSuccess(true);
        response.setData(userInfoVO);
        return response;
    }

    @Facade
    @Override
    public UserOperatorResponse modify(UserModifyRequest request) {
        return null;
    }

    @Facade
    @Override
    public UserOperatorResponse auth(UserAuthRequest request) {
        return null;
    }

    @Facade
    @Override
    public UserOperatorResponse active(UserActiveRequest request) {
        return null;
    }
}
