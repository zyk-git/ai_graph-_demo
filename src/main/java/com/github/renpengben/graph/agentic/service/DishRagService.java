package com.github.renpengben.graph.agentic.service;

import com.github.renpengben.graph.agentic.entity.Dish;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 轻量 RAG：从本地 {@code dish_corpus.txt} 加载菜品摘要行，按关键词重叠做 TopN 召回；召回的 id 再交给下游用数据库查详情。
 *
 * <p>语料行格式示例（单行一条）：
 * <pre>"id":1,"name":"麻婆豆腐","category":"热菜",...</pre>
 */
@Slf4j
@Service
public class DishRagService {

  private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

  /** 内存索引：每行语料 + 解析出的 dish id */
  private final List<CorpusEntry> corpus = new ArrayList<>();

  @Value("${app.rag.dish-corpus-path:dish_corpus.txt}")
  private String dishCorpusPath;

  @PostConstruct
  public void loadCorpusFromFile() {
    corpus.clear();
    List<String> lines = readCorpusLines();
    for (String line : lines) {
      if (line == null) continue;
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
      Integer id = parseId(trimmed);
      if (id == null) {
        log.warn("跳过无法解析 id 的语料行: {}", trimmed.length() > 120 ? trimmed.substring(0, 120) + "..." : trimmed);
        continue;
      }
      corpus.add(new CorpusEntry(id, trimmed));
    }
    log.info("RAG 语料已加载: {} 条, 来源配置 app.rag.dish-corpus-path={}", corpus.size(), dishCorpusPath);
  }

  /**
   * 从本地语料检索候选菜品 id；若语料为空则返回空列表（由上游决定是否走 DB 兜底）。
   */
  public List<Integer> retrieveDishIds(String queryText, int topN) {
    if (corpus.isEmpty()) {
      log.debug("RAG 语料为空，跳过检索");
      return List.of();
    }

    List<String> qTokens = tokenize(queryText);
    Set<String> qSet = Set.copyOf(qTokens);

    List<Scored> scored = new ArrayList<>();
    for (CorpusEntry e : corpus) {
      int score = overlapScore(qSet, tokenize(e.lineText()));
      scored.add(new Scored(e.id(), score));
    }

    return scored.stream()
        .sorted(Comparator.<Scored>comparingInt(s -> s.score).reversed().thenComparingInt(s -> s.id))
        .filter(s -> s.score > 0)
        .limit(Math.max(1, topN))
        .map(s -> s.id)
        .collect(Collectors.toList());
  }

  /** 供导出测试：由 {@link Dish} 生成与语料文件一致的摘要行。 */
  public String buildDishSnippet(Dish d) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", d.getId());
    m.put("name", d.getName());
    m.put("category", d.getCategory());
    m.put("cuisine", d.getCuisine());
    m.put("taste", d.getTaste());
    m.put("calories", d.getCalories());
    m.put("allergen", d.getAllergen());
    m.put("main_ingredient", d.getMainIngredient());
    m.put("custom_options", d.getCustomOptions());
    m.put("seasonal", d.getSeasonal());

    return m.entrySet().stream()
        .map(en -> "\"" + en.getKey() + "\":" + quoteIfNeeded(en.getValue()))
        .collect(Collectors.joining(","));
  }

  private List<String> readCorpusLines() {
    // 1) 工作目录下的文件
    try {
      Path path = Path.of(dishCorpusPath);
      if (Files.isRegularFile(path)) {
        log.info("RAG 读取语料文件(本地路径): {}", path.toAbsolutePath());
        return Files.readAllLines(path, StandardCharsets.UTF_8);
      }
    } catch (Exception e) {
      log.warn("读取语料文件失败: {}", e.getMessage());
    }

    // 2) classpath:dish_corpus.txt（可放在 src/main/resources）
    try {
      Resource res = new ClassPathResource("dish_corpus.txt");
      if (res.exists()) {
        log.info("RAG 读取语料文件(classpath): dish_corpus.txt");
        try (BufferedReader br =
            new BufferedReader(
                new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
          return br.lines().collect(Collectors.toList());
        }
      }
    } catch (Exception e) {
      log.warn("从 classpath 加载 dish_corpus.txt 失败: {}", e.getMessage());
    }

    log.warn(
        "未找到 dish 语料文件。请将 dish_corpus.txt 放在工作目录 [{}] 或 resources classpath 下。",
        Path.of(dishCorpusPath).toAbsolutePath());
    return List.of();
  }

  private static Integer parseId(String line) {
    Matcher m = ID_PATTERN.matcher(line);
    if (m.find()) {
      return Integer.parseInt(m.group(1));
    }
    return null;
  }

  private static String quoteIfNeeded(Object v) {
    if (v == null) return "\"\"";
    if (v instanceof Number) return String.valueOf(v);
    String s = String.valueOf(v).replace("\"", "\\\"");
    return "\"" + s + "\"";
  }

  private static int overlapScore(Set<String> queryTokens, List<String> docTokens) {
    int s = 0;
    for (String t : docTokens) {
      if (queryTokens.contains(t)) s++;
    }
    return s;
  }

  private static List<String> tokenize(String text) {
    if (text == null || text.isBlank()) return List.of();
    String normalized = text.toLowerCase(Locale.ROOT);
    String[] parts = normalized.split("[^\\p{IsHan}\\p{Alnum}]+");
    List<String> out = new ArrayList<>();
    for (String p : parts) {
      if (p == null) continue;
      String s = p.trim();
      if (s.isEmpty()) continue;
      out.add(s);
    }
    return out.stream().filter(Objects::nonNull).toList();
  }

  private record CorpusEntry(int id, String lineText) {}

  private record Scored(int id, int score) {}
}
