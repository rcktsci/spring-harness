package se.rocketscien.harness.intelligence;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import java.util.Optional;

@Repository
public interface LlmCredentialsRepository extends CrudRepository<LlmCredentials, UUID> {
    Optional<LlmCredentials> findById(UUID id);
}
