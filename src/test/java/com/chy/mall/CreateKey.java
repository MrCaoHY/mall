package com.chy.mall;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;

/**
 * 本地开发密钥生成工具；仅在受控本机运行，输出不能提交或上传到共享测试日志。
 */
public class CreateKey {
    @Test
    public void createKey(){
        SecretKey build = Jwts.SIG.HS256.key().build();
        String key = Encoders.BASE64.encode(build.getEncoded());
        System.out.println(key);
    }

}
