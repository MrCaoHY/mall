package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.LoginUserRequest;
import com.chy.mall.dto.RegisterUserRequest;
import com.chy.mall.entity.UserDo;
import com.chy.mall.enums.UserRole;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.UserMapper;
import com.chy.mall.service.UserService;
import com.chy.mall.util.JwtUtil;
import com.chy.mall.vo.LoginVo;
import com.chy.mall.vo.UserVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 注册使用BCrypt存储密码，登录统一凭证错误提示；日志仅记录结果和用户ID。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /** 编码后入库；用户名冲突交给唯一键最终裁决，不保存原始密码。 */
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
        log.info("event=user_registered userId={} role=USER", registerUser.getId());
        return userVo;
    }

    /** 未找到账号和密码不匹配均返回相同提示，避免向调用方暴露账号是否存在。 */
    @Override
    public LoginVo login(LoginUserRequest request) {
        String rawPassword = request.getPassword();
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ErrorCode.LOGIN_CREDENTIALS_INVALID);
        }
        UserDo user = userMapper.selectOne(Wrappers.<UserDo>lambdaQuery().eq(UserDo::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.LOGIN_CREDENTIALS_INVALID);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        LoginVo loginVo = new LoginVo();
        loginVo.setToken(token);
        loginVo.setUser(userDoToVo(user));
        // Token只返回客户端，日志只保留用户主键和服务端角色。
        log.info("event=login_succeeded userId={} role={}", user.getId(), user.getRole());
        return loginVo;
    }

    /** 显式选取公开字段，防止密码随实体直接序列化给客户端。 */
    private UserVo userDoToVo(UserDo userDo) {
        UserVo userVo = new UserVo();
        userVo.setId(userDo.getId());
        userVo.setUsername(userDo.getUsername());
        userVo.setRole(userDo.getRole());
        userVo.setEnabled(userDo.getEnabled());
        return userVo;
    }
}
