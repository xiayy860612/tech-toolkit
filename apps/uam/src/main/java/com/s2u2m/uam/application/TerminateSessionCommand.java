package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.SessionRef;

/** 强制下线的输入：操作人用于自我保护规则与写操作日志，{@code target} 是要终止的会话引用。 */
public record TerminateSessionCommand(OperatorContext operator, SessionRef target) {}
