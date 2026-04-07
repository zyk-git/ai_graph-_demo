
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chain")
public class ChainController {

  private final CompiledGraph compiledGraph;

  @Autowired
  public ChainController(@Qualifier("chainGraph") StateGraph chainGraph)
      throws GraphStateException {
    SaverConfig saverConfig = SaverConfig.builder().build();
    // 编译时可设中断点
    this.compiledGraph = chainGraph
        .compile(CompileConfig.builder().saverConfig(saverConfig).interruptBefore("step4").build());
  }

  @PostMapping
  public Map<String, Object> chain(@RequestBody String text) {
    return compiledGraph.invoke(Map.of("inputText", text)).get().data();
  }

  @PostMapping(path = "/stream", produces = "text/event-stream")
  public Flux<Map<String, Object>> chainStream(@RequestBody String text) {
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

  @GetMapping
  public Map<String, Object> chainGet(@RequestParam("text") String text) {
    return compiledGraph.invoke(Map.of("inputText", text)).get().data();
  }

  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<Map<String, Object>> chainStreamGet(@RequestParam("text") String text) {
    RunnableConfig cfg = RunnableConfig.builder()
            .streamMode(CompiledGraph.StreamMode.SNAPSHOTS)
            .build();
    return Flux.create(sink -> {
      compiledGraph.stream(Map.of("inputText", text), cfg)
              .forEachAsync(node -> sink.next(node.state().data()))
              .whenComplete((v, e) -> {
                if (e != null) sink.error(e);
                else sink.complete();
              });
    });
  }
}
