//package com.github.renpengben.graph.agentic.patterns.chain;
//
//import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
//import com.alibaba.cloud.ai.graph.OverAllState;
//import com.alibaba.cloud.ai.graph.action.NodeAction;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.vectorstore.SearchRequest;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//import java.util.Map;
//
//
//public class RagNode implements NodeAction {
//    private final VectorStore vectorStore;  // 例如 Chroma, PGVector
//    private final DashScopeApi.EmbeddingModel embeddingModel;
//
//    public RagNode(VectorStore vectorStore, DashScopeApi.EmbeddingModel embeddingModel) {
//        this.vectorStore = vectorStore;
//        this.embeddingModel = embeddingModel;
//    }
//
//    @Override
//    public Map<String, Object> apply(OverAllState state) {
//        // 1. 获取用户画像和偏好
//        String userProfile = (String) state.value("userProfile").orElse("{}");
//        String preferences = (String) state.value("preferences").orElse("{}");
//
//        // 2. 构造查询语句（可用 LLM 改写，也可直接拼接）
//        String query = String.format("用户目标：%s，喜好：%s，忌口：%s",
//                extractGoal(userProfile), preferences, extractAllergies(userProfile));
//
//        // 3. 向量检索 top-K 菜品
//        List<Document> docs = vectorStore.similaritySearch(
//            SearchRequest.query(query).withTopK(10)
//        );
//
//        // 4. 可选：额外进行关键词过滤（例如排除过敏原）
//        List<Map<String, Object>> filtered = docs.stream()
//            .filter(doc -> !containsAllergen(doc, extractAllergies(userProfile)))
//            .map(this::docToMap)
//            .collect(Collectors.toList());
//
//        return Map.of("retrievedDishes", filtered);
//    }
//}