package com.chy.mall.vo;

import com.chy.mall.enums.UserRole;
import lombok.Data;

/**
 * 用户公开信息，不包含密码编码值。
 */
@Data
public class UserVo {
    /** 用户主键。 */
    private Long id;

    /** 登录用户名。 */
    private String username;

    /** 用户角色。 */
    private UserRole role;

    /** 账号是否启用。 */
    private Boolean enabled;
}
