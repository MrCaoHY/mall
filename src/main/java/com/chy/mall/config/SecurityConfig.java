package com.chy.mall.config;

import com.chy.mall.security.JwtAuthenticationFilter;
import com.chy.mall.security.RestAccessDeniedHandler;
import com.chy.mall.security.RestAuthenticationEntryPoint;
import com.chy.mall.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtUtil jwtUtil;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtUtil);
        //关闭默认规则
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                //不用session
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                //加入处理器
                .exceptionHandling(exception->exception
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                .accessDeniedHandler(restAccessDeniedHandler))
                //配置接口权限
                .authorizeHttpRequests(authorize->authorize.requestMatchers(
                        "/users/register",
                        "/users/login",
                        "/error"
                ).permitAll().requestMatchers(HttpMethod.GET,"/products/**").permitAll()
                                .requestMatchers("/products/**").hasRole("ADMIN")
                        //authenticated表示需要登录不限制权限
                                .requestMatchers("/orders/**").authenticated()
                                .anyRequest().authenticated()
                        //过滤器放进安全链
                ).addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

}
