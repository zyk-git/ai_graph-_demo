package com.github.renpengben.graph.agentic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.renpengben.graph.agentic.dto.UserProfile;
import com.github.renpengben.graph.agentic.entity.UserProfileEntity;
import com.github.renpengben.graph.agentic.mapper.UserProfileMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserProfileService {

  private final UserProfileMapper userProfileMapper;
  private final ObjectMapper objectMapper;

  public UserProfileService(UserProfileMapper userProfileMapper, ObjectMapper objectMapper) {
    this.userProfileMapper = userProfileMapper;
    this.objectMapper = objectMapper;
  }

  public UserProfile loadByUsername(String username) throws Exception {
    // username唯一：这里用最简方式实现（需要更高性能可改为Wrapper/XML）
    List<UserProfileEntity> all = userProfileMapper.selectList(null);
    UserProfileEntity entity =
        all.stream()
            .filter(e -> e != null && username != null && username.equals(e.getUsername()))
            .findFirst()
            .orElse(null);
    if (entity == null) {
      return null;
    }

    UserProfile profile = new UserProfile();
    UserProfile.User user = new UserProfile.User();
    user.setAge(entity.getAge());
    user.setGoal(entity.getGoal());
    user.setSpiceLevel(entity.getSpiceLevel());
    user.setAllergies(parseJsonArray(entity.getAllergies()));
    profile.setUser(user);
    profile.setLikes(parseJsonArray(entity.getLikes()));
    return profile;
  }

  private List<String> parseJsonArray(String json) throws Exception {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    return objectMapper.readValue(json, new TypeReference<>() {});
  }
}

