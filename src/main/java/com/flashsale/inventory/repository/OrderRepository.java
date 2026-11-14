package com.flashsale.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.flashsale.inventory.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    Optional<Order> findByOrderNo(UUID orderNo);

    long countByProductId(Long productId);
}
