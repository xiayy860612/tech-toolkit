package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.ClientRejection;

/** 拒绝日志端口：每次 401 / 403 记一条结构化日志（FR-043），只写应用日志。 */
public interface ClientRejectionLog {

  void record(ClientRejection rejection);
}
