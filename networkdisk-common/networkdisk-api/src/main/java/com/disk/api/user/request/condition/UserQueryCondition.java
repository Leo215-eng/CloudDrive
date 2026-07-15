package com.disk.api.user.request.condition;

import java.io.Serializable;

/**
 * @author Hollis
 */
/** * 用户查询条件的统一父接口。 * * 这个接口本身不定义方法，
 * 主要作用是把不同类型的查询条件统一归类： * 比如按用户ID查询、按邮箱查询、按手机号查询，都可以实现这个接口。
 * * * 这样 UserQueryRequest 中只需要保存一个 UserQueryCondition 字段， * 就可以接收各种不同的查询条件对象。
 * * * 继承 Serializable 是因为这些请求对象可能会在 Dubbo RPC 调用中跨服务传输， * 需要支持序列化。 */
public interface UserQueryCondition extends Serializable {

}
