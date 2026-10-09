package com.chy.mall.control;

import com.chy.mall.common.ApiResponse;
import com.chy.mall.dto.RegisterUserRequest;
import com.chy.mall.service.UserService;
import com.chy.mall.vo.UserVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    /**
     * 注册普通用户，角色和启用状态由服务端设置。
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserVo>> register(@Valid @RequestBody RegisterUserRequest request) {
        UserVo user = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(user));
    }
}
