package com.disk.files.infrastructure.es.entity;

import lombok.Data;
import org.dromara.easyes.annotation.HighLight;
import org.dromara.easyes.annotation.IndexField;
import org.dromara.easyes.annotation.IndexId;
import org.dromara.easyes.annotation.IndexName;
import org.dromara.easyes.annotation.rely.Analyzer;
import org.dromara.easyes.annotation.rely.FieldType;
import org.dromara.easyes.annotation.rely.IdType;

import java.util.Date;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@Data
//Easy-ES 核心注解，指定这个实体对应 ES 里的哪个索引，作用完全等同于 MyBatis-Plus 的 @TableName("user_file")。
//这里索引名是 user_file_index，和数据库 user_file 表一一对应，数据同步时两边 ID 保持一致。
@IndexName("user_file_index")
public class UserFileESEntity {

    /** 主键ID（对应 ES _id） */
    @IndexId(type = IdType.CUSTOMIZE)
    //    标记这个字段是 ES 文档的唯一主键（对应 ES 原生的 _id 字段），等同于数据库表的主键 ID。
//    type = IdType.CUSTOMIZE：主键类型为「自定义」，也就是我们自己控制 ID 值。
//    这里直接用数据库里文件的自增 ID 当 ES 的文档 ID，两边 ID 完全对应，方便数据同步和查询。
    private Long id;

    /** 用户ID */
    @IndexField(fieldType = FieldType.KEYWORD, value = "user_id")
//    这是最常用的注解，用来配置 ES 索引里每个字段的类型、名称等属性，核心参数：
//    fieldType：字段在 ES 中的数据类型（最核心）；
//    value：ES 里真实的字段名（Java 用驼峰，ES 用下划线，和数据库命名对齐）。
    private Long userId;

    /** 父级文件夹ID */
    @IndexField(fieldType = FieldType.KEYWORD, value = "parent_id")
    private Long parentId;

    /** 真实文件ID（物理文件） */
    @IndexField(fieldType = FieldType.KEYWORD, value = "real_file_id")
    private Long realFileId;

    /** 文件名（分词、支持搜索、高亮） */
    @HighLight(mappingField = "filename")
    @IndexField(fieldType = FieldType.TEXT, analyzer = Analyzer.IK_SMART, value = "filename")
    private String filename;

    /** 文件夹标识（1=文件夹，0=文件） */
    @IndexField(fieldType = FieldType.INTEGER, value = "folder_flag")
    private Integer folderFlag;

    /** 文件大小描述（如“12MB”） */
    @IndexField(fieldType = FieldType.KEYWORD, value =  "file_size_desc")
    private String fileSizeDesc;

    /** 文件类型（如“pdf”、“jpg”、“docx”等） */
    @IndexField(fieldType = FieldType.KEYWORD, value = "file_type")
    private Integer fileType;

    /** 创建人 */
    @IndexField(fieldType = FieldType.KEYWORD, value = "create_user")
    private String createUser;

    /** 创建时间 */
    @IndexField(fieldType = FieldType.DATE, value = "gmt_create")
    private Date gmtCreate;

    /** 修改时间 */
    @IndexField(fieldType = FieldType.DATE, value = "gmt_modified")
    private Date gmtModified;

    /** 更新人 */
    @IndexField(fieldType = FieldType.KEYWORD, value = "update_user")
    private String updateUser;

    /** 删除标志（0=正常，1=已删除） */
    @IndexField(fieldType = FieldType.INTEGER, value = "deleted")
    private Integer deleted;

    /** 乐观锁版本号 */
    @IndexField(fieldType = FieldType.INTEGER, value = "lock_version")
    private Integer lockVersion;
}
