package com.knowbase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * KnowBase AI 业务后端入口。
 *
 * <p>本服务是业务数据与权限的唯一权威源：
 * 用户、角色、知识库、文档、会话、审计全部在此落库。
 * Python AI 服务只做计算，不持有任何权限判断逻辑。
 */
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class KnowBaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowBaseApplication.class, args);
    }
}
