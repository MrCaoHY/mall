package com.chy.mall.vo;

import lombok.Data;
import lombok.ToString;

/**
 * 登录成功结果。
 */
@Data
public class LoginVo {
    /** 后续请求放入Authorization请求头的JWT。 */
    @ToString.Exclude
    private String token;

    /** 当前登录用户的公开信息。 */
    private UserVo user;
}
