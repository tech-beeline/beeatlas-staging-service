package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "tc", schema = "staging")
public class Tc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tc_code", unique = true, nullable = false)
    private String tcCode;

    @Column(name = "product_id")
    private Integer productId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
