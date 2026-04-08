package com.github.renpengben.graph.agentic.patterns.chain;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.GraphRepresentation.Type;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.renpengben.graph.agentic.dto.UserProfile;
import com.github.renpengben.graph.agentic.service.DishRagService;
import com.github.renpengben.graph.agentic.tools.DishQueryTools;
import com.github.renpengben.graph.agentic.tools.UserProfileTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EatGraphConfiguration {
  @Bean
  public StateGraph eatGraph(
      UserProfileTools userProfileTools,
      DishRagService dishRagService,
      DishQueryTools dishQueryTools,
      ObjectMapper objectMapper)
      throws GraphStateException {
    OverAllStateFactory factory =
        () -> {
          OverAllState s = new OverAllState();
          s.registerKeyAndStrategy("username", new ReplaceStrategy()); // 前端传入username
          s.registerKeyAndStrategy("profile", new ReplaceStrategy()); // UserProfile
          s.registerKeyAndStrategy("retrievedDishIds", new ReplaceStrategy()); // List<Integer>
          s.registerKeyAndStrategy("recommendedDishes", new ReplaceStrategy()); // List<Map> detail
          s.registerKeyAndStrategy("resultText", new ReplaceStrategy()); // 最终文本
          return s;
        };

    StateGraph graph = new StateGraph("EatGraph", factory.create());
    graph.addNode("loadProfile", node_async(new LoadProfileNode(userProfileTools)));
    graph.addNode("ragRetrieve", node_async(new RagRetrieveNode(dishRagService)));
    graph.addNode("retrieveDishes", node_async(new RetrieveDishesNode(dishQueryTools)));
    graph.addNode("composeText", node_async(new ComposeTextNode()));

    graph.addEdge(START, "loadProfile");
    graph.addEdge("loadProfile", "ragRetrieve");
    graph.addEdge("ragRetrieve", "retrieveDishes");
    graph.addEdge("retrieveDishes", "composeText");
    graph.addEdge("composeText", END);

    GraphRepresentation representation = graph.getGraph(Type.PLANTUML, "eat recommendation flow");
    System.out.println("\n=== EatGraph UML Flow ===");
    System.out.println(representation.content());
    System.out.println("==================================\n");

    return graph;
  }

  static class LoadProfileNode implements NodeAction {
    private final UserProfileTools userProfileTools;

    LoadProfileNode(UserProfileTools userProfileTools) {
      this.userProfileTools = userProfileTools;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
      String username = (String) state.value("username").orElse("");
      if (username == null || username.isBlank()) {
        return Map.of("profile", null);
      }
      UserProfile profile = userProfileTools.getUserProfileByUsername(username.trim());
      if (profile == null) {
        throw new GraphStateException("用户不存在");
      }
      return Map.of("profile", profile);
    }
  }

  static class RagRetrieveNode implements NodeAction {
    private final DishRagService dishRagService;

    RagRetrieveNode(DishRagService dishRagService) {
      this.dishRagService = dishRagService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
      UserProfile profile = (UserProfile) state.value("profile").orElse(null);
      String q = buildQuery(profile);
      List<Integer> ids = dishRagService.retrieveDishIds(q, 5);
      return Map.of("retrievedDishIds", ids);
    }

    private static String buildQuery(UserProfile profile) {
      if (profile == null || profile.getUser() == null) return "";
      StringBuilder sb = new StringBuilder();
      if (profile.getUser().getGoal() != null) sb.append(profile.getUser().getGoal()).append(" ");
      if (profile.getUser().getSpiceLevel() != null) sb.append(profile.getUser().getSpiceLevel()).append(" ");
      if (profile.getUser().getAllergies() != null) sb.append(String.join(" ", profile.getUser().getAllergies())).append(" ");
      if (profile.getLikes() != null) sb.append(String.join(" ", profile.getLikes()));
      return sb.toString().trim();
    }
  }

  static class RetrieveDishesNode implements NodeAction {
    private final DishQueryTools dishQueryTools;

    RetrieveDishesNode(DishQueryTools dishQueryTools) {
      this.dishQueryTools = dishQueryTools;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
      UserProfile profile = (UserProfile) state.value("profile").orElse(null);
      if (profile == null || profile.getUser() == null) {
        return Map.of("recommendedDishes", List.of());
      }

      String goal = safe(profile.getUser().getGoal());
      List<String> allergies = profile.getUser().getAllergies() == null ? List.of() : profile.getUser().getAllergies();
      String excludeAllergen = allergies.isEmpty() ? null : safe(allergies.get(0));
      Integer maxCalories = goal.contains("减脂") ? 500 : null;

      @SuppressWarnings("unchecked")
      List<Integer> ragIds = (List<Integer>) state.value("retrievedDishIds").orElse(List.of());

      List<Map<String, Object>> base = new ArrayList<>();
      if (ragIds != null) {
        for (Integer id : ragIds) {
          if (id == null) continue;
          Map<String, Object> detail = dishQueryTools.getDishDetail(id);
          if (detail != null && !detail.containsKey("error")) {
            base.add(detail);
          }
        }
      }
      // RAG 为空时兜底：仍然从 DB 走 tools 做一次基础检索（保证候选来自数据库）
      if (base.isEmpty()) {
        base = dishQueryTools.queryDishes(null, null, null, maxCalories, excludeAllergen);
      }

      String spiceLevel = safe(profile.getUser().getSpiceLevel());
      List<String> likes = profile.getLikes() == null ? List.of() : profile.getLikes();

      List<Map<String, Object>> filtered =
          base.stream()
              .filter(Objects::nonNull)
              .filter(d -> matchesSpice(spiceLevel, safe((String) d.get("taste"))))
              .filter(d -> matchesLikes(likes, d))
              .sorted((a, b) -> Integer.compare(score(profile, b), score(profile, a)))
              .limit(3)
              .collect(Collectors.toList());

      return Map.of("recommendedDishes", filtered);
    }

    private static boolean matchesLikes(List<String> likes, Map<String, Object> dish) {
      if (likes == null || likes.isEmpty()) {
        return true;
      }
      String name = safe((String) dish.get("name"));
      String mainIngredient = safe((String) dish.get("mainIngredient"));
      String text = (name + " " + mainIngredient).toLowerCase(Locale.ROOT);
      // 只要命中任一 like 即可（避免过严导致无结果）
      return likes.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
          .map(s -> s.toLowerCase(Locale.ROOT))
          .anyMatch(text::contains);
    }

    private static boolean matchesSpice(String spiceLevel, String taste) {
      if (spiceLevel.isBlank()) {
        return true;
      }
      String t = taste.toLowerCase(Locale.ROOT);
      return switch (spiceLevel) {
        case "不辣" -> !t.contains("辣") && !t.contains("麻");
        case "微辣" -> t.contains("辣") || t.contains("鲜");
        case "中辣" -> t.contains("辣") || t.contains("麻");
        case "重辣" -> t.contains("辣") || t.contains("麻");
        default -> true;
      };
    }

    private static int score(UserProfile profile, Map<String, Object> dish) {
      int s = 0;
      String goal = profile.getUser() == null ? "" : safe(profile.getUser().getGoal());
      Integer calories = dish.get("calories") instanceof Number n ? n.intValue() : null;
      if (goal.contains("减脂") && calories != null) {
        if (calories <= 350) s += 3;
        else if (calories <= 500) s += 1;
        else s -= 2;
      }
      List<String> likes = profile.getLikes() == null ? List.of() : profile.getLikes();
      String name = safe((String) dish.get("name"));
      String mainIngredient = safe((String) dish.get("mainIngredient"));
      String text = (name + " " + mainIngredient).toLowerCase(Locale.ROOT);
      for (String like : likes) {
        if (like != null && !like.isBlank() && text.contains(like.trim().toLowerCase(Locale.ROOT))) {
          s += 2;
        }
      }
      return s;
    }

    private static String safe(String s) {
      return s == null ? "" : s.trim();
    }
  }

  static class ComposeTextNode implements NodeAction {
    @Override
    public Map<String, Object> apply(OverAllState state) {
      UserProfile profile = (UserProfile) state.value("profile").orElse(null);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> dishes = (List<Map<String, Object>>) state.value("recommendedDishes").orElse(List.of());

      StringBuilder sb = new StringBuilder();
      sb.append("基于用户画像的菜品推荐结果：\n\n");
      if (profile != null && profile.getUser() != null) {
        sb.append("用户画像：")
            .append("年龄 ").append(profile.getUser().getAge()).append("，目标 ").append(profile.getUser().getGoal())
            .append("，过敏原 ").append(profile.getUser().getAllergies() == null ? "无" : profile.getUser().getAllergies())
            .append("，辣度 ").append(profile.getUser().getSpiceLevel())
            .append("，偏好 ").append(profile.getLikes() == null ? "无" : profile.getLikes())
            .append("\n\n");
      }

      if (dishes == null || dishes.isEmpty()) {
        sb.append("未检索到满足条件的菜品（已按过敏原/热量/口味偏好过滤）。");
        return Map.of("resultText", sb.toString());
      }

      int idx = 1;
      for (Map<String, Object> dish : dishes) {
        sb.append("### 推荐菜品 ").append(idx++).append("\n");
        sb.append(formatDishDetail(dish)).append("\n");
        sb.append("推荐理由：").append(buildReason(profile, dish)).append("\n\n");
      }

      return Map.of("resultText", sb.toString());
    }

    private static String formatDishDetail(Map<String, Object> d) {
      // 输出“详细信息”（尽量覆盖核心字段，但避免冗长）
      List<String> lines = new ArrayList<>();
      lines.add(kv("id", d.get("id")));
      lines.add(kv("name", d.get("name")));
      lines.add(kv("category", d.get("category")));
      lines.add(kv("cuisine", d.get("cuisine")));
      lines.add(kv("mainIngredient", d.get("mainIngredient")));
      lines.add(kv("auxiliaryIngredient", d.get("auxiliaryIngredient")));
      lines.add(kv("seasoning", d.get("seasoning")));
      lines.add(kv("allergen", d.get("allergen")));
      lines.add(kv("cookingMethod", d.get("cookingMethod")));
      lines.add(kv("prepTimeMinutes", d.get("prepTimeMinutes")));
      lines.add(kv("taste", d.get("taste")));
      lines.add(kv("calories", d.get("calories")));
      lines.add(kv("sellingPrice", d.get("sellingPrice")));
      lines.add(kv("customOptions", d.get("customOptions")));
      return lines.stream().filter(s -> !s.endsWith(": ")).collect(Collectors.joining("\n"));
    }

    private static String buildReason(UserProfile profile, Map<String, Object> dish) {
      if (profile == null || profile.getUser() == null) {
        return "匹配用户偏好与菜品属性。";
      }
      List<String> reasons = new ArrayList<>();

      String goal = profile.getUser().getGoal() == null ? "" : profile.getUser().getGoal();
      Integer calories = dish.get("calories") instanceof Number n ? n.intValue() : null;
      if (goal.contains("减脂") && calories != null) {
        reasons.add("目标为减脂，热量约 " + calories + "kcal，优先选择更低热量方案。");
      }

      String spice = profile.getUser().getSpiceLevel() == null ? "" : profile.getUser().getSpiceLevel();
      String taste = dish.get("taste") == null ? "" : String.valueOf(dish.get("taste"));
      if (!spice.isBlank() && !taste.isBlank()) {
        reasons.add("偏好辣度为 " + spice + "，该菜口味为 " + taste + "。");
      }

      List<String> likes = profile.getLikes() == null ? List.of() : profile.getLikes();
      String name = dish.get("name") == null ? "" : String.valueOf(dish.get("name"));
      String mainIngredient = dish.get("mainIngredient") == null ? "" : String.valueOf(dish.get("mainIngredient"));
      List<String> hitLikes =
          likes.stream()
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .filter(l -> (name + " " + mainIngredient).contains(l))
              .toList();
      if (!hitLikes.isEmpty()) {
        reasons.add("命中偏好食材/关键词：" + hitLikes + "。");
      }

      List<String> allergies = profile.getUser().getAllergies() == null ? List.of() : profile.getUser().getAllergies();
      if (!allergies.isEmpty()) {
        reasons.add("已按过敏原排除：" + allergies + "。");
      }

      if (reasons.isEmpty()) {
        return "该菜品在口味、热量与偏好食材维度综合匹配用户需求。";
      }
      return String.join("；", reasons);
    }

    private static String kv(String k, Object v) {
      return k + ": " + (v == null ? "" : v);
    }
  }
}

