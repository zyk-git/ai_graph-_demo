package com.github.renpengben.graph.agentic.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.renpengben.graph.agentic.entity.Dish;
import com.github.renpengben.graph.agentic.mapper.DishMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class DishQueryTools {

  private final DishMapper dishMapper;

  public DishQueryTools(DishMapper dishMapper) {
    this.dishMapper = dishMapper;
  }

  @Tool(
      description =
          "根据条件查询菜品。支持按名称关键词、类别、菜系、最大热量、排除过敏原筛选。返回菜品列表(包含id、name、category、cuisine、taste、calories、sellingPrice、allergen、customOptions、prepTimeMinutes、mainIngredient等)。")
  public List<Map<String, Object>> queryDishes(
      @ToolParam(description = "菜品名称关键词，模糊匹配；可为空") String name,
      @ToolParam(description = "类别：凉菜/热菜/主食/汤品；可为空") String category,
      @ToolParam(description = "菜系：川/粤/鲁/苏/浙/闽/湘/徽；可为空") String cuisine,
      @ToolParam(description = "最大热量（千卡）；可为空") Integer maxCalories,
      @ToolParam(description = "需要排除的过敏原，如'海鲜'、'坚果'；可为空") String excludeAllergen) {

    LambdaQueryWrapper<Dish> qw = new LambdaQueryWrapper<>();
    if (name != null && !name.isBlank()) {
      qw.like(Dish::getName, name.trim());
    }
    if (category != null && !category.isBlank()) {
      qw.eq(Dish::getCategory, category.trim());
    }
    if (cuisine != null && !cuisine.isBlank()) {
      qw.eq(Dish::getCuisine, cuisine.trim());
    }
    if (maxCalories != null) {
      qw.le(Dish::getCalories, maxCalories);
    }
    if (excludeAllergen != null && !excludeAllergen.isBlank()) {
      qw.and(w -> w.isNull(Dish::getAllergen).or().notLike(Dish::getAllergen, excludeAllergen.trim()));
    }

    qw.orderByAsc(Dish::getId);
    List<Dish> dishes = dishMapper.selectList(qw);

    return dishes.stream()
        .filter(Objects::nonNull)
        .map(
            dish -> {
              Map<String, Object> map = new HashMap<>();
              map.put("id", dish.getId());
              map.put("name", dish.getName());
              map.put("category", dish.getCategory());
              map.put("cuisine", dish.getCuisine());
              map.put("taste", dish.getTaste());
              map.put("calories", dish.getCalories());
              map.put("sellingPrice", dish.getSellingPrice());
              map.put("allergen", dish.getAllergen());
              map.put("customOptions", dish.getCustomOptions());
              map.put("prepTimeMinutes", dish.getPrepTimeMinutes());
              map.put("mainIngredient", dish.getMainIngredient());
              return map;
            })
        .toList();
  }

  @Tool(description = "根据菜品ID获取详细信息（包含主料/辅料/调料/过敏原/烹饪方式/营养/成本等字段）。")
  public Map<String, Object> getDishDetail(@ToolParam(description = "菜品ID") Integer dishId) {
    if (dishId == null) {
      return Map.of("error", "dishId 不能为空");
    }
    Dish dish = dishMapper.selectById(dishId);
    if (dish == null) {
      return Map.of("error", "菜品不存在");
    }
    Map<String, Object> map = new HashMap<>();
    map.put("id", dish.getId());
    map.put("name", dish.getName());
    map.put("category", dish.getCategory());
    map.put("cuisine", dish.getCuisine());
    map.put("mainIngredient", dish.getMainIngredient());
    map.put("auxiliaryIngredient", dish.getAuxiliaryIngredient());
    map.put("seasoning", dish.getSeasoning());
    map.put("allergen", dish.getAllergen());
    map.put("cookingMethod", dish.getCookingMethod());
    map.put("prepTimeMinutes", dish.getPrepTimeMinutes());
    map.put("temperatureRequirement", dish.getTemperatureRequirement());
    map.put("taste", dish.getTaste());
    map.put("texture", dish.getTexture());
    map.put("color", dish.getColor());
    map.put("aroma", dish.getAroma());
    map.put("portionSize", dish.getPortionSize());
    map.put("calories", dish.getCalories());
    map.put("nutritionInfo", dish.getNutritionInfo());
    map.put("shelfLife", dish.getShelfLife());
    map.put("storageCondition", dish.getStorageCondition());
    map.put("ingredientCost", dish.getIngredientCost());
    map.put("sellingPrice", dish.getSellingPrice());
    map.put("grossProfit", dish.getGrossProfit());
    map.put("customOptions", dish.getCustomOptions());
    map.put("seasonal", dish.getSeasonal());
    map.put("createdAt", dish.getCreatedAt());
    return map;
  }
}

