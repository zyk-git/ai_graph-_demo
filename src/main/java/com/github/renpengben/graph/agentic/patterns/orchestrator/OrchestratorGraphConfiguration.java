package com.github.renpengben.graph.agentic.patterns.orchestrator;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Configuration
public class OrchestratorGraphConfiguration {

  @Bean
  public StateGraph orchestratorGraph(ChatModel chatModel) throws GraphStateException {
    ChatClient client = ChatClient.builder(chatModel).defaultAdvisors(new SimpleLoggerAdvisor())
        .build();
    OverAllStateFactory factory = () -> {
      OverAllState s = new OverAllState();
      s.registerKeyAndStrategy("inputText", new ReplaceStrategy());
      s.registerKeyAndStrategy("taskResult", new ReplaceStrategy());
      return s;
    };
    StateGraph graph = new StateGraph("OrchestratorGraph", factory.create())
        // 注册节点
            .addNode("orchestrator",node_async(new OrchestratorNode(client)))
            .addNode("synthesizer",node_async(new SynthesizerNode()))
        .addEdge(START, "orchestrator")
       .addEdge("orchestrator","synthesizer")
        .addEdge("synthesizer",END);

    // 可视化
    GraphRepresentation representation = graph.getGraph(GraphRepresentation.Type.PLANTUML,
        "orchestrator  flow");
    System.out.println("\n=== Orchestrator  UML Flow ===");
    System.out.println(representation.content());
    System.out.println("==================================\n");

    return graph;
  }
  //编排节点
  static class OrchestratorNode implements NodeAction {
    private final ChatClient client;
    OrchestratorNode(ChatClient client) {
      this.client=client;
    }
    @Override
    public Map<String, Object> apply(OverAllState state) throws GraphStateException {
    String DEFAULT_ORCHESTRATOR_PROMPT = """
			Analyze this task and break it down into 2-3 distinct approaches:

			Task: {task}

			Return your response in this JSON format:
			\\{
			"analysis": "Explain your understanding of the task and which variations would be valuable.
			             Focus on how each approach serves different aspects of the task.",
			"tasks": [
				\\{
				"type": "formal",
				"description": "Write a precise, technical version that emphasizes specifications"
				\\},
				\\{
				"type": "conversational",
				"description": "Write an engaging, friendly version that connects with readers"
				\\}
			]
			\\}
			""";
      String text = (String) state.value("inputText").orElse("");
      OrchestratorResponse orchestratorResponse = client.prompt()
              .user(u -> u.text(DEFAULT_ORCHESTRATOR_PROMPT)
                      .param("task", text))
              .call()
              .entity(OrchestratorResponse.class);
      //任务拆改后,批量并行执行子任务
      OverAllStateFactory factory = () -> {
        OverAllState s = new OverAllState();
        s.registerKeyAndStrategy("inputText", new ReplaceStrategy());
        s.registerKeyAndStrategy("result",new AppendStrategy());
          s.registerKeyAndStrategy("outputText",new ReplaceStrategy());
        return s;
      };
      StateGraph stateGraph = new StateGraph(() -> factory.create());
      for (int i = 0; i < orchestratorResponse.tasks.size(); i++) {
        stateGraph.addNode("task"+i,node_async(new WorkerLlmNode(client,orchestratorResponse.tasks.get(i))))
                .addEdge(START,"task"+i)
                .addEdge("task"+i,END);

      }
        // 可视化
        GraphRepresentation representation = stateGraph.getGraph(GraphRepresentation.Type.PLANTUML,
                "orchestrator task  flow");
        System.out.println("\n=== Orchestrator Task  UML Flow ===");
        System.out.println(representation.content());
        System.out.println("==================================\n");
        CompileConfig compileConfig = CompileConfig.builder().saverConfig(SaverConfig.builder().build()).build();
        Map<String, Object> taskResults = stateGraph.compile(compileConfig).invoke(Map.of("inputText", text)).get().data();
        ArrayList result =(ArrayList) taskResults.get("result");
        return Map.of("taskResult",result);
    }
  }
  //结果合并节点
  static class SynthesizerNode implements NodeAction {

      @Override
      public Map<String, Object> apply(OverAllState state) throws Exception {
          Object result = state.value("result").orElse("");
          return Map.of("outputText", result);
      }
  }
  //工作处理节点
  static class WorkerLlmNode implements NodeAction {
    private ChatClient client;
    private Task task;
    String DEFAULT_WORKER_PROMPT = """
			Generate content based on:
			Task: {original_task}
			Style: {task_type}
			Guidelines: {task_description}
			""";

    public WorkerLlmNode(ChatClient client,Task task) {
      this.client = client;
      this.task=task;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
      String taskDescription = (String) state.value("inputText").orElse("");
      String result = client.prompt()
              .user(u -> u.text(DEFAULT_WORKER_PROMPT)
                      .param("original_task", taskDescription)
                      .param("task_type", task.type())
                      .param("task_description", task.description()))
              .call()
              .content();
      return Map.of("result", result);
    }
  }
  public record OrchestratorResponse(String analysis, List<Task> tasks) {
  }
  public record Task(String type, String description) {
  }
}
