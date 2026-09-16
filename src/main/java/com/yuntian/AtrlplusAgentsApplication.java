package com.yuntian;

import dev.langchain4j.openai.spring.AutoConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(exclude = AutoConfig.class)
@EnableDiscoveryClient
@EnableFeignClients
public class AtrlplusAgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AtrlplusAgentsApplication.class, args);
    }

}
