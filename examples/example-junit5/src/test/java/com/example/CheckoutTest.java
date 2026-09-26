package com.example;

import dev.reportinglabs.core.Rl;
import dev.reportinglabs.core.annotations.*;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Owner("naveen")
@Feature("checkout")
class CheckoutTest {

    @Test
    @Priority("P0")
    @Severity("blocker")
    @Story("SHOP-231")
    @Epic("EPIC-18")
    void places_an_order_with_a_saved_card() {
        Map<String, Object> cart = new LinkedHashMap<>();
        cart.put("user", "demo@shop.io");
        cart.put("card", "4242 4242 4242 4242");
        cart.put("total", 99.90);
        cart.put("currency", "USD");
        Rl.testData("Cart snapshot", cart);

        Rl.log("opening checkout");
        Rl.log("filling saved card");
        Rl.log("submitting order");

        Rl.api("POST", "/api/orders", 201, 340);
        Assertions.assertTrue(true);
    }

    @Test
    @Priority("P0")
    @Severity("blocker")
    @Issue("SHOP-800")
    void locks_account_after_5_failures() {
        Rl.log("attempting 6th password");
        Rl.api("POST", "/api/auth/login", 401, 220);
        Assertions.assertEquals("Account locked, try again in 15 minutes", "Locked",
            "auth service should return a friendly locked message");
    }

    @Test
    @Priority("P1")
    @Severity("major")
    @Owner("rahul")
    void applies_coupon_code_SAVE10() {
        Rl.log("applying SAVE10 to cart");
        Assertions.assertTrue(true);
    }

    @Test
    @Priority("P2")
    @Severity("minor")
    @Owner("priya")
    @Feature("shipping")
    void shows_shipping_estimate() {
        Rl.log("computing shipping");
        Assertions.assertTrue(true);
    }
}
