package com.github.renpengben.graph.agentic.controller;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import java.util.Map;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/eat")
public class EatController {

  private final CompiledGraph compiledGraph;

  @Autowired
  public EatController(@Qualifier("eatGraph") StateGraph eatGraph) throws GraphStateException {
    SaverConfig saverConfig = SaverConfig.builder().build();
    this.compiledGraph = eatGraph.compile(CompileConfig.builder().saverConfig(saverConfig).build());
  }

  @PostMapping(path = "/recommend", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> recommend(@RequestBody String username) {
    Map<String, Object> map = compiledGraph.invoke(Map.of("username", username)).get().data();
    log.info("result: {}", map);
    return map;
  }

  @PostMapping(path = "/recommend/{username}", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> recommendByUsername(@PathVariable("username") String username) {
    Map<String, Object> map = compiledGraph.invoke(Map.of("username", username)).get().data();
    log.info("result: {}", map);
    return map;
  }
}

