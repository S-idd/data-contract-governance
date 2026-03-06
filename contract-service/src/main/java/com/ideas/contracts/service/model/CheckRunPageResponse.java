package com.ideas.contracts.service.model;

import java.util.List;

public record CheckRunPageResponse(
    List<CheckRunResponse> items,
    int limit,
    int offset,
    boolean hasMore) {}
