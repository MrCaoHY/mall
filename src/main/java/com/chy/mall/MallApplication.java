package com.chy.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// 已使用数据库登录与JWT，关闭会生成并打印开发密码的默认内存用户配置。
/**
 * 商城应用启动入口，由Spring Boot创建容器并加载配置与组件。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }

}
