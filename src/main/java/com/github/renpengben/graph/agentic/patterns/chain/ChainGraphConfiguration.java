package com.github.renpengben.graph.agentic.patterns.chain;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.GraphRepresentation.Type;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChainGraphConfiguration {


  @Bean
  public StateGraph chainGraph(ChatModel chatModel) throws GraphStateException {

    ChatClient client = ChatClient.builder(chatModel).defaultAdvisors(new SimpleLoggerAdvisor())
        .build();
    /**
     * 创建状态工厂，定义图中所有状态键及其更新策略
     * 注册了5个状态键用于在节点间传递数据：
     * - inputText: 原始输入文本
     * - step1Text: 步骤1处理后的文本（提取数值和指标）
     * - step2Text: 步骤2处理后的文本（转换为百分比格式）
     * - step3Text: 步骤3处理后的文本（按数值降序排序）
     * - result: 最终结果（格式化为Markdown表格）
     * 所有状态键均使用ReplaceStrategy策略，新值直接替换旧值
     */
    OverAllStateFactory factory = () -> {
      OverAllState s = new OverAllState();
      s.registerKeyAndStrategy("inputText", new ReplaceStrategy());
      s.registerKeyAndStrategy("step1Text", new ReplaceStrategy());
      s.registerKeyAndStrategy("step2Text", new ReplaceStrategy());
      s.registerKeyAndStrategy("step3Text", new ReplaceStrategy());
      s.registerKeyAndStrategy("result", new ReplaceStrategy());
      return s;
    };
    // Step 1
    String step1System = """
        Extract only the numerical values and their associated metrics from the text.
        Format each as'value: metric' on a new line.
        Example format:
        92: customer satisfaction
        45%: revenue growth""";
    // Step 2
    String step2System = """
        Convert all numerical values to percentages where possible.
        If not a percentage or points, convert to decimal (e.g., 92 points -> 92%).
        Keep one number per line.
        Example format:
        92%: customer satisfaction
        45%: revenue growth""";
    // Step 3
    String step3System = """
        Sort all lines in descending order by numerical value.
        Keep the format 'value: metric' on each line.
        Example:
        92%: customer satisfaction
        87%: employee satisfaction""";
    // Step 4
    String step4System = """
        Format the sorted data as a markdown table with columns:
        | Metric | Value |
        |:--|--:|
        | Customer Satisfaction | 92% | """;
    StateGraph graph = new StateGraph("ChainGraph", factory.create());
    graph.addNode("step1", node_async(new StepNode(client, step1System, "inputText", "step1Text")));
    graph.addNode("gate", node_async(new GateNode()));
    graph.addNode("step2", node_async(new StepNode(client, step2System, "step1Text", "step2Text")));
    graph.addNode("step3", node_async(new StepNode(client, step3System, "step2Text", "step3Text")));
    graph.addNode("step4", node_async(new StepNode(client, step4System, "step3Text", "result")));
    graph.addEdge(START, "step1");
    graph.addEdge("step1", "gate")
        .addConditionalEdges("gate", edge_async(t -> {
              String checked = (String) t.value("checked").orElse("fail");
              return checked;
            }),
            Map.of("pass", "step2", "fail", END));
    graph.addEdge("step2", "step3");
    graph.addEdge("step3", "step4");
    graph.addEdge("step4", END);
    // 可视化
    GraphRepresentation representation = graph.getGraph(Type.PLANTUML,
        "chain flow");
    System.out.println("\n=== Chain UML Flow ===");
    System.out.println(representation.content());
    System.out.println("==================================\n");

    return graph;
  }

  //分步骤处理节点
  static class StepNode implements NodeAction {

    private final ChatClient client;
    private final String systemPrompt;
    private final String inputKey;
    private final String outputKey;

    StepNode(ChatClient client, String systemPrompt, String inputKey, String outputKey) {
      this.client = client;
      this.systemPrompt = systemPrompt;
      this.inputKey = inputKey;
      this.outputKey = outputKey;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
      String text = (String) state.value(inputKey).orElse("");
      // 调用 LLM
      ChatResponse resp = client.prompt().system(systemPrompt).user(text).call()
          .chatResponse();
      String stepResult = resp.getResult().getOutput().getText();
      return Map.of(outputKey, stepResult);
    }
  }
  //程序检测节点 programmatic checks非必须
  static class GateNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) {
      //一些程序性的检测，如果未通过流程结束
      Map<String, Object> checkResultMap = new HashMap<>();
      checkResultMap.put("checked", "pass");
      // checkResultMap.put("checked",fail);
      return checkResultMap;
    }
  }
}
