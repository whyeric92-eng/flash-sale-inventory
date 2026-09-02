package com.flashsale.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.flashsale.inventory.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
