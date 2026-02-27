package com.s2u2m.examples.service_example_h2;

import com.s2u2m.examples.service_example_h2.domain.account.UsernameAccount;
import com.s2u2m.examples.service_example_h2.domain.account.UsernameAccountRepository;
import com.s2u2m.examples.service_example_h2.domain.rbac.Permission;
import com.s2u2m.examples.service_example_h2.domain.rbac.PermissionRepository;
import com.s2u2m.examples.service_example_h2.domain.rbac.Resource;
import com.s2u2m.examples.service_example_h2.domain.rbac.Role;
import com.s2u2m.examples.service_example_h2.domain.rbac.RoleName;
import com.s2u2m.examples.service_example_h2.domain.rbac.RoleRepository;
import com.s2u2m.examples.service_example_h2.domain.user.InnerUser;
import com.s2u2m.examples.service_example_h2.domain.user.UserRepository;
import com.s2u2m.examples.service_example_h2.repository.DemoEntity;
import com.s2u2m.examples.service_example_h2.repository.DemoEntityRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@AllArgsConstructor
public class AppDataInit {

  private final UserRepository userRepository;
  private final UsernameAccountRepository usernameAccountRepository;
  private final RoleRepository roleRepository;
  private final DemoEntityRepository demoEntityRepository;
  private final PermissionRepository permissionRepository;

  @Transactional
  @PostConstruct
  void init() {
    InnerUser user = new InnerUser();
    user.setName("admin");
    user.setAvatar("");
    user = userRepository.save(user);

    UsernameAccount account = new UsernameAccount("admin", "{noop}123456");
    account.setUser(user);
    account = usernameAccountRepository.save(account);

    List<Role> roles =
        List.of(new Role(user.getId(), RoleName.ADMIN), new Role(user.getId(), RoleName.USER));
    roleRepository.saveAll(roles);

    DemoEntity demo = new DemoEntity();
    demo.setName("test");
    demo = demoEntityRepository.save(demo);

    List<Permission> permissions = List.of(
      new Permission(user.getId(), Resource.DEMO, "", "create"),
      new Permission(user.getId(), Resource.DEMO, demo.getId(), "write"));
    permissionRepository.saveAll(permissions);
  }
}
