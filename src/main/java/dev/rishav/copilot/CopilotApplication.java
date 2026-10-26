package dev.rishav.copilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
public class CopilotApplication {
    public static void main(String[] args) { SpringApplication.run(CopilotApplication.class, args); }
}
