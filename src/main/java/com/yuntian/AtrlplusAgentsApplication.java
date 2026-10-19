package com.yuntian;

import dev.langchain4j.openai.spring.AutoConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

// 数据库仅由可选学习模块管理，禁止关闭模块时触发默认数据源/Flyway 自动初始化。
@SpringBootApplication(exclude = {AutoConfig.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class})
@EnableDiscoveryClient
@EnableFeignClients
public class AtrlplusAgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AtrlplusAgentsApplication.class, args);
    }

}
