package com.chy.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.chy.mall.enums.UserRole;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 用户表实体，仅用于持久化，接口响应使用独立 VO。
 */
@TableName("users")
@Data
public class UserDo {
    /** 数据库自增的用户主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 唯一登录用户名。 */
    private String username;

    /** PasswordEncoder 编码后的密码值；字段名保留 password，日志不输出该值。 */
    @ToString.Exclude
    private String password;

    /** 用户角色，由应用层设置为 USER 或 ADMIN。 */
    private UserRole role;

    /** true 对应数据库 1，false 对应 0；使用包装类型保留可空字段的语义。 */
    private Boolean enabled;

    /** 数据库默认生成的创建时间。 */
    private LocalDateTime createdAt;

    /** 数据库默认生成、更新时维护的时间。 */
    private LocalDateTime updatedAt;
}
