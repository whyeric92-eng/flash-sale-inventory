package com.flashsale.inventory.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.flashsale.inventory.dto.ProductRequest;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.ProductRepository;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Product getProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product " + id + " not found"));
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Product createProduct(ProductRequest request) {
        Product product = new Product();
        product.setProductName(request.productName());
        return productRepository.save(product);
    }

    public Product updateProduct(Long id, ProductRequest request) {
        Product product = getProduct(id);
        product.setProductName(request.productName());
        return productRepository.save(product);
    }

    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new NotFoundException("Product " + id + " not found");
        }
        productRepository.deleteById(id);
    }
}
