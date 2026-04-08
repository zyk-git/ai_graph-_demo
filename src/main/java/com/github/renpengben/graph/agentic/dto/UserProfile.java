package com.github.renpengben.graph.agentic.dto;

import java.util.List;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
public class UserProfile {

  private User user;
  private List<String> likes;

  @Data
  public static class User {
    private Integer age;
    private String goal;
    private List<String> allergies;
    @JsonProperty("spice_level")
    private String spiceLevel;
  }
}

