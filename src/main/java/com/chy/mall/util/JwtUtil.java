package com.chy.mall.util;

import com.chy.mall.config.JwtProperties;
import com.chy.mall.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;


/**
 * JWT签发与验签工具。Base64用于还原密钥字节，不是加密；Claims只能在验签通过后读取。
 */
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProperties;

    private SecretKey secretKey;

    /** 启动时还原签名密钥，配置错误应在应用接收请求前暴露；不要记录密钥内容。 */
    @PostConstruct
    private void init() {
        byte[] encodeKey = Decoders.BASE64.decode(jwtProperties.getSecret());
        this.secretKey =  Keys.hmacShaKeyFor(encodeKey);
    }
    /** subject存用户名，Claims存用户ID与角色，iat/exp限制Token有效期。 */
    public String generateToken(Long userId, String username, UserRole role){
        Instant now = Instant.now();
        Instant expiration = now.plus(jwtProperties.getExpiration());
        JwtBuilder jwtBuilder = Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey);
        return jwtBuilder.compact();
    }

    /** 验证签名和有效期后返回Claims，解析异常交给认证过滤器统一处理。 */
    public Claims parseToken(String token){
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }

    /** 从已经验签的Claims提取数字ID，不能直接解析未经验证的Token载荷。 */
    public Long getUserId(Claims claims){
        Number userId = claims.get("userId", Number.class);
        if (userId == null) {
            throw new IllegalArgumentException("Token缺少用户ID");
        }
        return userId.longValue();
    }
    /** 用户名沿用JWT标准subject字段。 */
    public String getUsername(Claims claims){
        return claims.getSubject();
    }

    /** 恢复服务端签发的角色枚举，非法角色按无效凭证处理。 */
    public UserRole getRole(Claims claims){
        String role = claims.get("role", String.class);
        return UserRole.valueOf(role);
    }
}
