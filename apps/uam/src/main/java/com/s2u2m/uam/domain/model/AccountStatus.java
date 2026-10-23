package com.s2u2m.uam.domain.model;

/** 账号状态；{@code DELETED} 为预留（逻辑删除，名称仍被占用），本期没有任何接口会写入。 */
public enum AccountStatus {
  ACTIVE,
  DISABLED,
  DELETED
}
