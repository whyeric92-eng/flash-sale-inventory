package com.flashsale.inventory.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.flashsale.inventory.entity.Order;
import com.flashsale.inventory.entity.OrderStatus;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.repository.OrderRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final ProductService productService;

    public OrderService(OrderRepository orderRepository, InventoryService inventoryService,
            ProductService productService) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.productService = productService;
    }

    public Order getOrder(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    public Order createOrder(Long productId, Integer quantity) {
        inventoryService.decreaseStock(productId, quantity);

        Product product = productService.getProduct(productId);

        Order order = new Order();
        order.setProduct(product);
        order.setQuantity(quantity);
        order.setStatus(OrderStatus.PENDING);

        return orderRepository.save(order);
    }

    public void deleteOrder(Long id) {
        orderRepository.deleteById(id);
    }
}
