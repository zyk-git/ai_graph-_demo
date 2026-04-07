package com.github.renpengben.graph.agentic;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.github.renpengben.graph.agentic.mapper")
public class EffectiveAgentSpringAlibabaGraphApplication {

  public static void main(String[] args) {
    SpringApplication.run(EffectiveAgentSpringAlibabaGraphApplication.class, args);
  }

}
