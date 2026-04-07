package com.github.renpengben.graph.agentic.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
    // 添加 StringHttpMessageConverter 支持文本类型
    converters.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
  }
}