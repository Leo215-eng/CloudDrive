package com.disk.api.user.request;

import com.disk.api.user.request.condition.UserEmailQueryCondition;
import com.disk.api.user.request.condition.UserIdQueryCondition;
import com.disk.api.user.request.condition.UserQueryCondition;
import com.disk.base.request.BaseRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 用户查询请求对象。
 *
 * 这个类用于封装“查询用户”这个动作需要的参数。
 * 它本身不关心具体是按用户ID查、按邮箱查，还是以后按手机号查，
 * 而是通过 UserQueryCondition 这个统一接口接收不同类型的查询条件。
 *
 * 设计目的：
 * 1. 统一查询请求入口：外部调用用户查询接口时，都传 UserQueryRequest。
 * 2. 支持多种查询方式：按ID查、按邮箱查等，都可以封装成不同的 Condition。
 * 3. 方便后续扩展：以后新增按手机号查询，只需要新增一个 UserPhoneQueryCondition，
 *    不需要大改 UserQueryRequest 的整体结构。
 *
 * @author weikunkun
 */
@Setter
@Getter
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class UserQueryRequest extends BaseRequest {

    /**
     * 用户查询条件。
     *
     * 这里字段类型写的是接口 UserQueryCondition，
     * 不是具体的 UserIdQueryCondition 或 UserEmailQueryCondition。
     *
     * 原因是：一次用户查询可能有不同的查询方式。
     * - 按用户ID查询：放入 UserIdQueryCondition
     * - 按邮箱查询：放入 UserEmailQueryCondition
     * - 以后按手机号查询：可以放入 UserPhoneQueryCondition
     *
     * 也就是说，这里使用的是 Java 多态：
     * 父接口类型 UserQueryCondition 可以接收它的任意实现类对象。
     *
     * 好处是 UserQueryRequest 不需要写很多字段：
     * private Long userId;
     * private String email;
     * private String phone;
     *
     * 而是统一写成：
     * private UserQueryCondition userQueryCondition;
     *
     * 具体按什么条件查，由实际放进去的 Condition 对象决定。
     */
    private UserQueryCondition userQueryCondition;

    /**
     * 根据用户ID构造查询请求。
     *
     * 调用方式：
     * new UserQueryRequest(1001L)
     *
     * 这个构造方法的作用是帮调用方少写几行代码。
     * 调用方不需要自己手动创建 UserIdQueryCondition，
     * 只需要传入 userId，这里会自动封装成“按用户ID查询”的条件对象。
     *
     * @param userId 用户ID，通常是用户表主键
     */
    public UserQueryRequest(Long userId) {
        /*
         * 创建一个“按用户ID查询”的具体条件对象。
         *
         * UserIdQueryCondition 是 UserQueryCondition 的实现类，
         * 表示当前这次查询使用 userId 作为查询条件。
         */
        UserIdQueryCondition userIdQueryCondition = new UserIdQueryCondition();

        /*
         * 把外部传入的 userId 设置到查询条件对象中。
         */
        userIdQueryCondition.setUserId(userId);

        /*
         * 把具体查询条件对象赋值给当前请求对象的 userQueryCondition 字段。
         *
         * this 表示当前正在创建的 UserQueryRequest 对象。
         * this.userQueryCondition 表示当前对象里的查询条件字段。
         *
         * 虽然字段类型是 UserQueryCondition 接口，
         * 但实际放进去的是 UserIdQueryCondition 实现类对象。
         */
        this.userQueryCondition = userIdQueryCondition;
    }

    /**
     * 根据用户邮箱构造查询请求。
     *
     * 调用方式：
     * new UserQueryRequest("xxx@qq.com")
     *
     * 这个构造方法同样是为了简化调用方代码。
     * 调用方只需要传入 email，这里会自动封装成“按邮箱查询”的条件对象。
     *
     * @param email 用户邮箱，通常用于登录、注册校验、用户信息查询等场景
     */
    public UserQueryRequest(String email) {
        /*
         * 创建一个“按邮箱查询”的具体条件对象。
         *
         * UserEmailQueryCondition 是 UserQueryCondition 的实现类，
         * 表示当前这次查询使用 email 作为查询条件。
         */
        UserEmailQueryCondition userEmailQueryCondition = new UserEmailQueryCondition();

        /*
         * 把外部传入的邮箱设置到查询条件对象中。
         */
        userEmailQueryCondition.setEmail(email);

        /*
         * 把具体查询条件对象赋值给当前请求对象的 userQueryCondition 字段。
         *
         * 虽然字段类型是 UserQueryCondition 接口，
         * 但实际放进去的是 UserEmailQueryCondition 实现类对象。
         *
         * 后续服务层拿到 UserQueryRequest 后，
         * 可以根据 userQueryCondition 的真实类型判断：
         * 如果是 UserIdQueryCondition，就按ID查；
         * 如果是 UserEmailQueryCondition，就按邮箱查。
         */
        this.userQueryCondition = userEmailQueryCondition;
    }
}