
package com.github.renpengben.graph.agentic.controller;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/parallel")
public class ParallelController {

  private final CompiledGraph compiledGraph;

  @Autowired
  public ParallelController(@Qualifier("parallelGraph") StateGraph parallelGraph)
      throws GraphStateException {
    SaverConfig saverConfig = SaverConfig.builder().build();
    // 编译时可设中断点
    this.compiledGraph = parallelGraph
        .compile(CompileConfig.builder().saverConfig(saverConfig).interruptBefore("aggregator").build());
  }

  @GetMapping
  public Map<String, Object> parallel(@RequestParam("text") String text) {
    return compiledGraph.invoke(Map.of("inputText", text)).get().data();
  }

  @GetMapping(path = "/stream", produces = "text/event-stream")
  public Flux<Map<String, Object>> parallelStream(@RequestParam("text") String text) {
    RunnableConfig cfg = RunnableConfig.builder().streamMode(CompiledGraph.StreamMode.SNAPSHOTS)
        .build();
    return Flux.create(sink -> {
      compiledGraph.stream(Map.of("inputText", text), cfg)
          .forEachAsync(node -> sink.next(node.state().data()))
          .whenComplete((v, e) -> {
            if (e != null) {
              sink.error(e);
            } else {
              sink.complete();
            }
          });
    });
  }

}
