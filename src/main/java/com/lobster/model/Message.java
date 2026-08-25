package com.lobster.model;

import java.util.List;

public record Message(
        String id,
        String sessionId,
        String role,
        List<Part> parts,
        long createdAt) {}
