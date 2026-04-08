package com.github.renpengben.graph.agentic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("dish")
public class Dish {

  @TableId(type = IdType.AUTO)
  private Integer id;

  private String name;
  private String category;
  private String cuisine;

  @TableField("main_ingredient")
  private String mainIngredient;

  @TableField("auxiliary_ingredient")
  private String auxiliaryIngredient;

  private String seasoning;
  private String allergen;

  @TableField("cooking_method")
  private String cookingMethod;

  @TableField("prep_time_minutes")
  private Integer prepTimeMinutes;

  @TableField("temperature_requirement")
  private String temperatureRequirement;

  private String taste;
  private String texture;
  private String color;
  private String aroma;

  @TableField("portion_size")
  private String portionSize;

  private Integer calories;

  @TableField("nutrition_info")
  private String nutritionInfo;

  @TableField("shelf_life")
  private String shelfLife;

  @TableField("storage_condition")
  private String storageCondition;

  @TableField("ingredient_cost")
  private BigDecimal ingredientCost;

  @TableField("selling_price")
  private BigDecimal sellingPrice;

  @TableField("gross_profit")
  private BigDecimal grossProfit;

  @TableField("custom_options")
  private String customOptions;

  private String seasonal;

  @TableField("created_at")
  private LocalDateTime createdAt;
}

