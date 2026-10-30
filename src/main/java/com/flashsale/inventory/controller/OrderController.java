package com.flashsale.inventory.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.flashsale.inventory.dto.CreateOrderRequest;
import com.flashsale.inventory.dto.OrderResponse;
import com.flashsale.inventory.service.OrderService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return OrderResponse.from(orderService.getOrder(id, userId));
    }

    @GetMapping
    public List<OrderResponse> getMyOrders(@AuthenticationPrincipal Long userId) {
        return orderService.getOrdersForUser(userId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateOrderRequest request) {
        return OrderResponse.from(orderService.createOrder(userId, request.productId(), request.quantity()));
    }
}
