package com.gateflow;

import com.gateflow.auth.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(AuthProperties.class)
@EnableScheduling
public class GateFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(GateFlowApplication.class, args);
    }
}
