package com.disk.api.user.request.condition;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * @author weikunkun
 */
/** * 按邮箱查询用户的条件对象。
 它是 UserQueryCondition 的一种具体实现，表示“这次查询用户时使用 email 作为查询条件”。
 当调用 new UserQueryRequest(email) 时，内部会创建这个对象，并把 email 设置进去。 */

@Getter
@Setter
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class UserEmailQueryCondition implements UserQueryCondition {
    /** 序列化版本号。
     由于 UserQueryCondition 继承了 Serializable，
     所以它的实现类也需要支持序列化。
     serialVersionUID 用来标识当前类的序列化版本，
     保证对象在序列化和反序列化时版本一致。 */
    private static final long serialVersionUID = -6080909404166212851L;

    /** * 用户邮箱。
     * 当前条件类只负责表达“按邮箱查询”，
     * 所以这里只保存 email 这一个查询字段。 */
    private String email;
}
