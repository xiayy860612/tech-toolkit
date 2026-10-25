package com.s2u2m.uam.domain.model;

/** 列表里的用户：用户本身与其关联的账号总数（含已停用账号）。 */
public record UserWithAccountCount(User user, long accountCount) {}
