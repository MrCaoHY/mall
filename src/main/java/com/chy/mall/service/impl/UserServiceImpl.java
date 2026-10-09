package com.chy.mall.service.impl;

import com.chy.mall.dto.LoginUserRequest;
import com.chy.mall.dto.RegisterUserRequest;
import com.chy.mall.entity.UserDo;
import com.chy.mall.enums.UserRole;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.UserMapper;
import com.chy.mall.service.UserService;
import com.chy.mall.vo.LoginVo;
import com.chy.mall.vo.UserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserVo register(RegisterUserRequest request) {
        String rawPassword = request.getPassword();
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_LONG);
        }
        //密码加盐保存
        String encodePassword = passwordEncoder.encode(rawPassword);
        UserDo registerUser = new UserDo();
        registerUser.setUsername(request.getUsername());
        registerUser.setPassword(encodePassword);
        registerUser.setRole(UserRole.USER);
        registerUser.setEnabled(true);
        int affectedRows;
        try {
            affectedRows = userMapper.insert(registerUser);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (affectedRows != 1) {
            throw new BusinessException(ErrorCode.USER_CREATE_FAILED);
        }
        UserVo userVo = new UserVo();
        userVo.setId(registerUser.getId());
        userVo.setUsername(request.getUsername());
        userVo.setRole(UserRole.USER);
        userVo.setEnabled(true);
        return userVo;
    }

    @Override
    public LoginVo login(LoginUserRequest request) {
        throw new UnsupportedOperationException("待实现用户登录");
    }
}
