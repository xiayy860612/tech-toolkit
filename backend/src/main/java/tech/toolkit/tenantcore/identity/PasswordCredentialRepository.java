package tech.toolkit.tenantcore.identity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, UUID> {}
