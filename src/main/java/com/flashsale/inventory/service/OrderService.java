package com.flashsale.inventory.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flashsale.inventory.entity.Order;
import com.flashsale.inventory.entity.OrderStatus;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.OrderRepository;
import com.flashsale.inventory.repository.UserRepository;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final ProductService productService;

    public OrderService(OrderRepository orderRepository, UserRepository userRepository,
            InventoryService inventoryService, ProductService productService) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.inventoryService = inventoryService;
        this.productService = productService;
    }

    public Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Order " + id + " not found"));
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    public Order createOrder(Long userId, Long productId, Integer quantity) {
        inventoryService.decreaseStock(productId, quantity);

        Product product = productService.getProduct(productId);

        Order order = new Order();
        // Reference only: the id comes from a verified token, and the FK rejects unknown users.
        order.setUser(userRepository.getReferenceById(userId));
        order.setProduct(product);
        order.setQuantity(quantity);
        order.setStatus(OrderStatus.PENDING);

        return orderRepository.save(order);
    }
}
