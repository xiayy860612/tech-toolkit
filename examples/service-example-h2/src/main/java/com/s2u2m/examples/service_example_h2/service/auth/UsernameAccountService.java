package com.s2u2m.examples.service_example_h2.service.auth;

import com.s2u2m.examples.service_example_h2.domain.account.UsernameAccount;
import com.s2u2m.examples.service_example_h2.domain.account.UsernameAccountRepository;
import com.s2u2m.examples.service_example_h2.domain.rbac.Permission;
import com.s2u2m.examples.service_example_h2.domain.rbac.PermissionRepository;
import com.s2u2m.examples.service_example_h2.domain.rbac.Role;
import com.s2u2m.examples.service_example_h2.domain.rbac.RoleRepository;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UsernameAccountService implements UserDetailsService {

  private final UsernameAccountRepository usernameAccountRepository;
  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    UsernameAccount account = usernameAccountRepository.findByUsername(username);
    if (Objects.isNull(account)) {
      throw new UsernameNotFoundException("username not found");
    }
    Long uid = account.getUser().getId();
    return new User(uid.toString(), account.getPassword(), getAuthorities(account));
  }

  private Collection<? extends GrantedAuthority> getAuthorities(UsernameAccount account) {
    Set<GrantedAuthority> permissions =
        permissionRepository.findByUid(account.getUser().getId()).stream()
            .map(Permission::getAuthority)
            .collect(Collectors.toSet());

    Set<GrantedAuthority> roles =
        roleRepository.findByUid(account.getUser().getId()).stream()
            .map(Role::getAuthority)
            .collect(Collectors.toSet());

    LinkedList<GrantedAuthority> authorities = new LinkedList<>(permissions);
    authorities.addAll(roles);
    return authorities;
  }
}
