package com.workorder;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class BCryptHashGeneratorTest {

    @Test
    void generateAdminHash() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("admin123");
        System.out.println("========================================");
        System.out.println("BCrypt hash for 'admin123':");
        System.out.println(hash);
        System.out.println("Hash length: " + hash.length());
        System.out.println("========================================");
    }
}
