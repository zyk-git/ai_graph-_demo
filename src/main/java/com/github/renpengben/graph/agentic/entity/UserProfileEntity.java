package com.github.renpengben.graph.agentic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("user_profile")
public class UserProfileEntity {

  @TableId(type = IdType.AUTO)
  private Integer id;

  private String username;
  private Integer age;
  private String gender;
  private BigDecimal height;
  private BigDecimal weight;
  private String goal;

  @TableField("activity_level")
  private String activityLevel;

  /** JSON string like ["海鲜","坚果"] */
  private String allergies;

  @TableField("spice_level")
  private String spiceLevel;

  /** JSON string like ["鸡肉","蔬菜"] */
  private String likes;

  @TableField("dietary_restriction")
  private String dietaryRestriction;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}

