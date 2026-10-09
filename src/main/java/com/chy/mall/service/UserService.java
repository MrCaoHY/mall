package com.chy.mall.service;

import com.chy.mall.dto.LoginUserRequest;
import com.chy.mall.dto.RegisterUserRequest;
import com.chy.mall.vo.LoginVo;
import com.chy.mall.vo.UserVo;

public interface UserService {
    UserVo register(RegisterUserRequest request);

    LoginVo login(LoginUserRequest request);
}
