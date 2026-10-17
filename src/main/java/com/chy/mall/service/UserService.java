package com.chy.mall.service;

import com.chy.mall.dto.LoginUserRequest;
import com.chy.mall.dto.RegisterUserRequest;
import com.chy.mall.vo.LoginVo;
import com.chy.mall.vo.UserVo;

/**
 * 用户业务契约：注册分配普通用户身份，登录核验密码后签发Token。
 */
public interface UserService {
    UserVo register(RegisterUserRequest request);

    LoginVo login(LoginUserRequest request);
}
