package com.github.renpengben.graph.agentic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.renpengben.graph.agentic.entity.Dish;
import com.github.renpengben.graph.agentic.mapper.DishMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 将数据库 dish 表导出为本地文本语料，供轻量 RAG 检索使用。
 *
 * <p>说明：该用例需要本机 MySQL 已创建数据库 dishs 且存在 dish 表数据。
 */
@SpringBootTest
@Disabled("需要本机MySQL(dishs)与dish表数据，手动启用后运行")
class DishCorpusExportTest {

  @Autowired private DishMapper dishMapper;
  @Autowired private DishRagService dishRagService;

  @Test
  void exportDishCorpusToLocalFile() throws Exception {
    List<Dish> dishes =
        dishMapper.selectList(
            new LambdaQueryWrapper<Dish>()
                .select(
                    Dish::getId,
                    Dish::getName,
                    Dish::getCategory,
                    Dish::getCuisine,
                    Dish::getTaste,
                    Dish::getCalories,
                    Dish::getAllergen,
                    Dish::getMainIngredient,
                    Dish::getCustomOptions,
                    Dish::getSeasonal));

    String content =
        dishes.stream().map(dishRagService::buildDishSnippet).reduce("", (a, b) -> a + b + System.lineSeparator());

    Path out = Path.of("dish_corpus.txt");
    Files.writeString(out, content, StandardCharsets.UTF_8);
  }
}

