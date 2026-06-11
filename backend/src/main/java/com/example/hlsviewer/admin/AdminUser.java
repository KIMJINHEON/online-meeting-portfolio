package com.example.hlsviewer.admin;

public record AdminUser(long id, String username, String passwordHash, String displayName) {}
