package com.github.renpengben.graph.agentic.patterns.parallel;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ParallelGraphConfiguration {

  @Bean
  public StateGraph parallelGraph(ChatModel chatModel) throws GraphStateException {
    ChatClient client = ChatClient.builder(chatModel).defaultAdvisors(new SimpleLoggerAdvisor())
        .build();
    OverAllStateFactory factory = () -> {
      OverAllState s = new OverAllState();
      s.registerKeyAndStrategy("inputText", new ReplaceStrategy());
      s.registerKeyAndStrategy("sentiment", new ReplaceStrategy());
      s.registerKeyAndStrategy("keywords", new ReplaceStrategy());
      s.registerKeyAndStrategy("aggregator", new ReplaceStrategy());
      return s;
    };
    StateGraph graph = new StateGraph("Parallel Flow", factory.create())
        // 注册节点
        .addNode("sentiment", node_async(new SentimentAnalysisNode(client, "inputText")))
        .addNode("keyword", node_async(new KeywordExtractionNode(client, "inputText")))
        .addNode("aggregator", node_async(new AggregatorNode()))
        // 构建并行边：使用单条边携带多目标
        .addEdge(START, "sentiment")
        .addEdge(START, "keyword")
        // 限制：sentiment/keyword 并行后必须合并到同一节点
        .addEdge("sentiment", "aggregator")
        .addEdge("keyword", "aggregator")

        .addEdge("aggregator", END);

    // 可视化
    GraphRepresentation representation = graph.getGraph(GraphRepresentation.Type.PLANTUML,
        "parallel  flow");
    System.out.println("\n=== Parallel  UML Flow ===");
    System.out.println(representation.content());
    System.out.println("==================================\n");

    return graph;
  }

  //情感分析节点
  static class SentimentAnalysisNode implements NodeAction {

    private final ChatClient client;

    private final String key;

    public SentimentAnalysisNode(ChatClient client, String key) {
      this.client = client;
      this.key = key;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
      String text = (String) state.value(key).orElse("");
      // 调用 LLM
      ChatResponse resp = client.prompt().user("情感分析: " + text).call()
          .chatResponse();
      String sentiment = resp.getResult().getOutput().getText();
      return Map.of("sentiment", sentiment);
    }

  }

  //关键字提取节点
  static class KeywordExtractionNode implements NodeAction {

    private final ChatClient client;

    private final String key;

    public KeywordExtractionNode(ChatClient client, String key) {
      this.client = client;
      this.key = key;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
      String text = (String) state.value(key).orElse("");
      ChatResponse resp = client.prompt().user("提取关键字: " + text).call()
          .chatResponse();
      String kws = resp.getResult().getOutput().getText();
      return Map.of("keywords", List.of(kws.split(",\\s*")));
    }

  }
 //并行结果合并节点
  static class AggregatorNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) {
      String sent = (String) state.value("sentiment").orElse("unknown");
      List<?> kws = (List<?>) state.value("keywords").orElse(List.of());
      return Map.of("analysis", Map.of("sentiment", sent, "keywords", kws));
    }

  }

}
