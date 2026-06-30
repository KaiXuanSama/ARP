package com.kaixuan.agentreproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.kaixuan.agentreproxy.config")
public class AgentreproxyApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentreproxyApplication.class, args);
	}

}
