package com.github.renpengben.graph.agentic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;

@SpringBootTest(
    classes = EffectiveAgentSpringAlibabaGraphApplicationTests.TestApplication.class,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
          + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
    })
class EffectiveAgentSpringAlibabaGraphApplicationTests {

  @TestConfiguration
  @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
  static class TestApplication {}

  @Test
  void contextLoads() {
  }

}
