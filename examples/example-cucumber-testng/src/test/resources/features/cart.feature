@cart @P1 @owner:naveen
Feature: Shopping cart

  Background:
    Given an empty cart

  @smoke
  Scenario: Add one item
    When I add 2 x "TEE-WHITE-M"
    Then the cart has 2 items
    And the total is 200

  Scenario: Coupon takes ten percent off
    When I add 1 x "JEAN-BLUE-32"
    And I apply coupon "SAVE10"
    Then the total is 90

  Scenario: Add several items from a table
    When I add these items
      | sku          | qty |
      | TEE-WHITE-M  | 1   |
      | JEAN-BLUE-32 | 3   |
    Then the cart has 4 items

  Scenario Outline: Quantities add up
    When I add <qty> x "<sku>"
    Then the cart has <qty> items
    Examples:
      | sku          | qty |
      | TEE-WHITE-M  | 1   |
      | CAP-RED      | 5   |
