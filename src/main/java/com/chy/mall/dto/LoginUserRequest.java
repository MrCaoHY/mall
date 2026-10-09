package com.chy.mall.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

/**
 * 用户登录请求。
 */
@Data
public class LoginUserRequest {
    /** 登录用户名。 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 原始密码，仅用于本次身份校验。 */
    @NotBlank(message = "密码不能为空")
    @ToString.Exclude
    private String password;
}
