package com.example;

import dev.reportinglabs.core.Rl;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.testng.Assert;

import java.util.LinkedHashMap;
import java.util.Map;

public class CartSteps {
    private final Map<String, Integer> cart = new LinkedHashMap<>();
    private String coupon;

    @Before
    public void reset() { cart.clear(); coupon = null; }

    @Given("an empty cart")
    public void emptyCart() { Rl.log("cart cleared"); }

    @When("I add {int} x {string}")
    public void add(int qty, String sku) { cart.merge(sku, qty, Integer::sum); }

    @When("I add these items")
    public void addMany(DataTable table) {
        for (Map<String, String> row : table.asMaps()) add(Integer.parseInt(row.get("qty")), row.get("sku"));
    }

    @When("I apply coupon {string}")
    public void applyCoupon(String code) { coupon = code; Rl.meta("coupon", code); }

    @Then("the cart has {int} items")
    public void hasItems(int expected) {
        int total = cart.values().stream().mapToInt(Integer::intValue).sum();
        Assert.assertEquals(total, expected, "items in cart");
    }

    @Then("the total is {int}")
    public void total(int expected) {
        int total = cart.values().stream().mapToInt(q -> q * 100).sum();
        if ("SAVE10".equals(coupon)) total = total * 90 / 100;
        Assert.assertEquals(total, expected, "cart total");
    }
}
