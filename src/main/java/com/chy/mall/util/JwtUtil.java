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


@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProperties;

    private SecretKey secretKey;

    @PostConstruct
    private void init() {
        byte[] encodeKey = Decoders.BASE64.decode(jwtProperties.getSecret());
        this.secretKey =  Keys.hmacShaKeyFor(encodeKey);
    }
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

    public Claims parseToken(String token){
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }

    public Long getUserId(Claims claims){
        Number userId = claims.get("userId", Number.class);
        return userId.longValue();
    }
    public String getUsername(Claims claims){
        return claims.getSubject();
    }

    public UserRole getRole(Claims claims){
        String role = claims.get("role", String.class);
        return UserRole.valueOf(role);
    }
}
