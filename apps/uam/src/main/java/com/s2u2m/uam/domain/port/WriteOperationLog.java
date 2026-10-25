package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.WriteOperationEvent;

/** 写操作日志端口：只写应用日志，不提供界面（FR-034）。 */
public interface WriteOperationLog {

  void record(WriteOperationEvent event);
}
