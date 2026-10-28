package com.xzwl.cloud.iam.api;

import java.util.List;

public record SessionDTO(boolean authenticated, List<String> roles, UserDTO user, String csrfToken, boolean primary) {}
