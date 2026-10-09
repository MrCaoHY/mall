package com.chy.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 用户注册请求；角色和启用状态由服务端决定。
 */
@Data
public class RegisterUserRequest {
    /** 登录用户名，长度为 3～32，仅允许字母、数字和下划线。 */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度必须为3～32个字符")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    /** 原始密码，长度为 8～64；业务层再校验 BCrypt 的 UTF-8 字节上限。 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度必须为8～64个字符")
    @ToString.Exclude
    private String password;
}
