package com.flashsale.inventory.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.flashsale.inventory.dto.AuthRequest;
import com.flashsale.inventory.dto.AuthResponse;
import com.flashsale.inventory.entity.User;
import com.flashsale.inventory.exception.ConflictException;
import com.flashsale.inventory.exception.InvalidCredentialsException;
import com.flashsale.inventory.repository.UserRepository;
import com.flashsale.inventory.security.JwtService;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public void register(AuthRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ConflictException("Username already taken");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        userRepository.save(user);
    }

    public AuthResponse login(AuthRequest request) {
        // Same error for unknown user and wrong password, so the endpoint can't be used to probe usernames.
        User user = userRepository.findByUsername(request.username())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        return new AuthResponse(jwtService.generateToken(user.getUsername()));
    }
}
