package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.ClientIdentity;
import java.util.Optional;

/** 识别客户端：输入服务密钥，输出客户端身份与权限；将来换成传输层身份只换它（research R4「接缝」）。 */
public interface ClientAuthenticator {

  Optional<ClientIdentity> authenticate(String serviceKey);
}
