package com.github.renpengben.graph.agentic.patterns.evaluatoroptimizer;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.github.renpengben.graph.agentic.patterns.evaluatoroptimizer.EvaluatorOptimizerConfiguration.EvaluateNode.EvaluateEdgeAction;
import com.github.renpengben.graph.agentic.patterns.evaluatoroptimizer.EvaluatorOptimizerConfiguration.EvaluateNode.EvaluationResponse.Evaluation;
import com.github.renpengben.graph.agentic.patterns.evaluatoroptimizer.EvaluatorOptimizerConfiguration.EvaluateNode.Generation;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EvaluatorOptimizerConfiguration {

  //生成解决方案默认提示词
  public static final String DEFAULT_GENERATOR_PROMPT = """
      Your goal is to complete the task based on the input. If there are feedback
      from your previous generations, you should reflect on them to improve your solution.

      CRITICAL: Your response must be a SINGLE LINE of valid JSON with NO LINE BREAKS except those explicitly escaped with \\n.
      Here is the exact format to follow, including all quotes and braces:

      {"thoughts":"Brief description here","response":"public class Example {\\n    // Code here\\n}"}

      Rules for the response field:
      1. ALL line breaks must use \\n
      2. ALL quotes must use \\"
      3. ALL backslashes must be doubled: \\
      4. NO actual line breaks or formatting - everything on one line
      5. NO tabs or special characters
      6. Java code must be complete and properly escaped

      Example of properly formatted response:
      {"thoughts":"Implementing counter","response":"public class Counter {\\n    private int count;\\n    public Counter() {\\n        count = 0;\\n    }\\n    public void increment() {\\n        count++;\\n    }\\n}"}

      Follow this format EXACTLY - your response must be valid JSON on a single line.
      """;

  //评估默认提示词
  public static final String DEFAULT_EVALUATOR_PROMPT = """
      Evaluate this code implementation for correctness, time complexity, and best practices.
      Ensure the code have proper javadoc documentation.
      Respond with EXACTLY this JSON format on a single line:

      {"evaluation":"PASS, NEEDS_IMPROVEMENT, or FAIL", "feedback":"Your feedback here"}

      The evaluation field must be one of: "PASS", "NEEDS_IMPROVEMENT", "FAIL"
      Use "PASS" only if all criteria are met with no improvements needed.
      """;

  @Bean
  public StateGraph evaluatorOptimizerGraph(ChatModel chatModel) throws GraphStateException {
    ChatClient client = ChatClient.builder(chatModel).defaultAdvisors(new SimpleLoggerAdvisor())
        .build();
    OverAllStateFactory factory = () -> {
      OverAllState s = new OverAllState();
      //任务
      s.registerKeyAndStrategy("task", new ReplaceStrategy());
      //生成内容和思考内容，值覆盖
      s.registerKeyAndStrategy("context", new ReplaceStrategy());
      //生成的内容的历史记录List<String>
      s.registerKeyAndStrategy("historyContext", new AppendStrategy());
      //List<Generation>历史生成内容对象
      s.registerKeyAndStrategy("chainOfThought", new AppendStrategy());
      //评估结果类型
      s.registerKeyAndStrategy("evaluationType", new ReplaceStrategy());

      return s;
    };
    StateGraph graph = new StateGraph("EvaluatorOptimizerGraph", factory.create())
        // 注册节点
        .addNode("generator", node_async(new GeneratorNode(client)))
        .addNode("evaluator", node_async(new EvaluateNode(client)))
        .addEdge(START, "generator")
        .addEdge("generator", "evaluator")
        .addConditionalEdges("evaluator", AsyncEdgeAction.edge_async(new EvaluateEdgeAction()),
            Map.of("PASS", END, "NEEDS_IMPROVEMENT", "generator", "FAIL", END));

    // 可视化
    GraphRepresentation representation = graph.getGraph(GraphRepresentation.Type.PLANTUML,
        "evaluator-optimizer  flow");
    System.out.println("\n=== EvaluatorOptimizer  UML Flow ===");
    System.out.println(representation.content());
    System.out.println("==================================\n");

    return graph;
  }

  /**
   * 生成内容节点
   */
  static class GeneratorNode implements NodeAction {

    private final ChatClient chatClient;
    private final String generatorPrompt;

    public GeneratorNode(ChatClient chatClient, String generatorPrompt) {
      this.chatClient = chatClient;
      this.generatorPrompt = generatorPrompt;
    }

    public GeneratorNode(ChatClient chatClient) {
      this.chatClient = chatClient;
      this.generatorPrompt = DEFAULT_GENERATOR_PROMPT;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
      String task = (String) state.value("task").orElse("");
      String context = (String) state.value("context").orElse("");
      Generation generationResponse = chatClient.prompt()
          .user(u -> u.text("{prompt}\n{context}\nTask: {task}")
              .param("prompt", this.generatorPrompt)
              .param("context", context)
              .param("task", task))
          .call()
          .entity(Generation.class);
      System.out.println(
          String.format("\n=== GENERATOR OUTPUT ===\nTHOUGHTS: %s\n\nRESPONSE:\n %s\n",
              generationResponse.thoughts(), generationResponse.response()));
      return Map.of("context", generationResponse.response, "historyContext",
          generationResponse.response, "chainOfThought",
          generationResponse);
    }
  }

  /**
   * 评估内容节点
   */
  static class EvaluateNode implements NodeAction {


    private final ChatClient chatClient;
    private final String evaluatorPrompt;

    public EvaluateNode(ChatClient chatClient, String evaluatorPrompt) {
      this.chatClient = chatClient;
      this.evaluatorPrompt = evaluatorPrompt;
    }

    public EvaluateNode(ChatClient chatClient) {
      this.chatClient = chatClient;
      this.evaluatorPrompt = DEFAULT_EVALUATOR_PROMPT;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
      String context = (String) state.value("context").orElse("");
      String task = (String) state.value("task").orElse("");
      EvaluationResponse evaluationResponse = chatClient.prompt()
          .user(u -> u.text("{prompt}\nOriginal task: {task}\nContent to evaluate: {content}")
              .param("prompt", evaluatorPrompt)
              .param("task", task)
              .param("content", context))
          .call()
          .entity(EvaluationResponse.class);
      System.out.println(
          String.format("\n===评估输出 ===\n评估内容: %s\n\n反馈结果: %s\n",
              evaluationResponse.evaluation(), evaluationResponse.feedback()));
      //累计结果
      List<String> historyContext = (List<String>) state.value("historyContext").orElse("");
      StringBuilder newContext = new StringBuilder();
      newContext.append("Previous attempts:");
      for (String hc : historyContext) {
        newContext.append("\n- ").append(hc);
      }
      newContext.append("\nFeedback: ").append(evaluationResponse.feedback());
      return Map.of("evaluationType", evaluationResponse.evaluation, "context",
          newContext.toString());
    }

    /**
     * 根据评估解决，选择下一步EdgeAction
     */
    static class EvaluateEdgeAction implements EdgeAction {


      @Override
      public String apply(OverAllState state) throws Exception {
        Evaluation evaluationType = (Evaluation) state.value("evaluationType").orElse("");
        return evaluationType.name();
      }
    }

    public record EvaluationResponse(Evaluation evaluation, String feedback) {

      public enum Evaluation {
        PASS, NEEDS_IMPROVEMENT, FAIL
      }
    }

    public record Generation(String thoughts, String response) {

    }
  }
}
