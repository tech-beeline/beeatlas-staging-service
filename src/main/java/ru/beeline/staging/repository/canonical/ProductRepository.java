package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.Product;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByUid(String uid);
}
