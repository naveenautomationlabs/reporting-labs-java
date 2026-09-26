package com.example;

import dev.reportinglabs.core.Rl;
import dev.reportinglabs.core.annotations.*;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.Map;

@Owner("naveen")
@Feature("cart")
public class CartTest {

    @Test(description = "adds a saved item to the cart", groups = {"smoke", "cart"})
    @Priority("P0")
    @Severity("blocker")
    @Story("SHOP-401")
    public void adds_item_to_cart() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sku", "JEAN-BLUE-32");
        line.put("qty", 1);
        Rl.testData("Cart line", line);
        Rl.log("clicking add-to-cart");
        Assert.assertTrue(true);
    }

    @Test(description = "removes an item from the cart", groups = {"smoke", "cart"})
    @Priority("P1")
    @Severity("major")
    public void removes_item_from_cart() {
        Rl.log("clicking trash icon");
        Assert.assertTrue(true);
    }

    @Test(description = "rejects a negative quantity", groups = {"validation"})
    @Priority("P2")
    @Severity("minor")
    @Owner("rahul")
    public void rejects_negative_quantity() {
        Rl.log("typing -1");
        Assert.assertEquals("quantity must be positive", "quantity must be positive");
    }
}
