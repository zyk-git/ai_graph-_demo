package com.github.renpengben.graph.agentic.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.renpengben.graph.agentic.tools.DishQueryTools;
import com.github.renpengben.graph.agentic.patterns.chain.EatGraphConfiguration;
import com.github.renpengben.graph.agentic.tools.UserProfileTools;
import com.github.renpengben.graph.agentic.dto.UserProfile;
import com.github.renpengben.graph.agentic.service.DishRagService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = EatControllerTest.TestApplication.class,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
          + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
    })
@AutoConfigureMockMvc
class EatControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private DishQueryTools dishQueryTools;
  @MockBean private UserProfileTools userProfileTools;
  @MockBean private DishRagService dishRagService;

  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
  @Import({EatController.class, EatGraphConfiguration.class})
  static class TestApplication {}

  @Test
  void recommend_shouldReturnTextFromDbDishes() throws Exception {
    UserProfile profile = new UserProfile();
    UserProfile.User user = new UserProfile.User();
    user.setAge(28);
    user.setGoal("减脂");
    user.setAllergies(List.of("海鲜"));
    user.setSpiceLevel("中辣");
    profile.setUser(user);
    profile.setLikes(List.of("鸡肉", "蔬菜", "低卡"));
    when(userProfileTools.getUserProfileByUsername(any())).thenReturn(profile);

    when(dishRagService.retrieveDishIds(any(), anyInt())).thenReturn(List.of(1));

    when(dishQueryTools.queryDishes(any(), any(), any(), any(), any()))
        .thenReturn(
            List.of(
                new HashMap<>() {
                  {
                    put("id", 1);
                    put("name", "低卡鸡胸沙拉");
                    put("category", "凉菜");
                    put("cuisine", "粤");
                    put("taste", "中辣");
                    put("calories", 320);
                    put("sellingPrice", 28.0);
                    put("allergen", "无");
                    put("customOptions", "少油");
                    put("prepTimeMinutes", 10);
                    put("mainIngredient", "鸡肉,蔬菜");
                  }
                }));

    when(dishQueryTools.getDishDetail(anyInt()))
        .thenReturn(
            new HashMap<>() {
              {
                put("id", 1);
                put("name", "低卡鸡胸沙拉");
                put("category", "凉菜");
                put("cuisine", "粤");
                put("mainIngredient", "鸡肉,蔬菜");
                put("auxiliaryIngredient", "");
                put("seasoning", "");
                put("allergen", "无");
                put("cookingMethod", "拌");
                put("prepTimeMinutes", 10);
                put("taste", "中辣");
                put("calories", 320);
                put("sellingPrice", 28.0);
                put("customOptions", "少油");
              }
            });

    mockMvc
        .perform(post("/eat/recommend").contentType(MediaType.APPLICATION_JSON).content("test_user"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultText").isNotEmpty())
        .andExpect(jsonPath("$.resultText").value(org.hamcrest.Matchers.containsString("低卡鸡胸沙拉")));
  }
}

