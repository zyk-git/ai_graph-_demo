package com.github.renpengben.graph.agentic.tools;

import com.github.renpengben.graph.agentic.dto.UserProfile;
import com.github.renpengben.graph.agentic.service.UserProfileService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class UserProfileTools {

  private final UserProfileService userProfileService;

  public UserProfileTools(UserProfileService userProfileService) {
    this.userProfileService = userProfileService;
  }

  @Tool(description = "根据username从user_profile表读取用户画像（包含age/goal/allergies/spice_level/likes）。")
  public UserProfile getUserProfileByUsername(
      @ToolParam(description = "用户名(username)，唯一") String username) throws Exception {
    return userProfileService.loadByUsername(username);
  }
}

