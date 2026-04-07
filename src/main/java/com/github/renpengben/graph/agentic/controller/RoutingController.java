package com.github.renpengben.graph.agentic.controller;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/routing")
public class RoutingController {
    private final CompiledGraph compiledGraph;
    @Autowired
    public RoutingController(@Qualifier("routingGraph") StateGraph routingGraph)
            throws GraphStateException {
        SaverConfig saverConfig = SaverConfig.builder().build();
        // 编译时可设中断点
        this.compiledGraph = routingGraph
                .compile(CompileConfig.builder().saverConfig(saverConfig).build());
    }
    @PostMapping
    public Map<String, Object> routing(@RequestBody String text) {
        return compiledGraph.invoke(Map.of("inputText", text)).get().data();
    }

    @PostMapping(path = "/stream", produces = "text/event-stream")
    public Flux<Map<String, Object>> routingStream(@RequestBody String text) {
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
