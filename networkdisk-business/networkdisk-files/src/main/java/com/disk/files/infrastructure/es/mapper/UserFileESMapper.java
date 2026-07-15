package com.disk.files.infrastructure.es.mapper;

import com.disk.files.infrastructure.es.entity.UserFileESEntity;
import org.dromara.easyes.core.kernel.BaseEsMapper;
import org.springframework.stereotype.Repository;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@Repository
//UserFileESMapper 操作 ES 的 user_file_index
//类似 MyBatis-Plus 的 Mapper 操作 MySQL 表
public interface UserFileESMapper extends BaseEsMapper<UserFileESEntity> {
}
