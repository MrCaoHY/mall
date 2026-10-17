package com.chy.mall.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * JWT配置绑定；密钥为保密配置，和Token本身都不能进入日志。
 */
@Component
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtProperties {
    /** Base64编码的签名密钥，从环境变量注入，禁止写入日志。 */
    @ToString.Exclude
    private String secret;
    /** Token有效期，使用Duration统一处理时间单位。 */
    private Duration expiration;
}
