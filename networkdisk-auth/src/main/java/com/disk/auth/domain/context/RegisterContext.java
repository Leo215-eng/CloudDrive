package com.disk.auth.domain.context;

import lombok.Getter;
import lombok.Setter;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@Getter
@Setter
//@Getter @Setter这是 Lombok 注解，
// 编译时会自动生成所有字段的 getXxx() / setXxx() 方法。所以虽然代码里看不到方法，但你可以直接调用
public class RegisterContext {

    /**
     * 验证码
     */
    private String checkCode;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 密码
     */
    private String password;

    /**
     * 昵称
     */
    private String nickName;

}
