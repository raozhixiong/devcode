package com.lobster.model;

public record Session(
        String id,
        String sessionKey,
        String kind,
        String title,
        String directory,
        long createdAt,
        long updatedAt) {}
