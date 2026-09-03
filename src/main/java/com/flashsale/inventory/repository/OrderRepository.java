package com.flashsale.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.flashsale.inventory.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
