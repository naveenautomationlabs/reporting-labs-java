package dev.reportinglabs.selenium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElementDescTest {

    @Test void singleHop() {
        assertEquals("id: login-button",
            RlSelenium.elementDesc("[[ChromeDriver: chrome on linux (3f1a)] -> id: login-button]"));
    }

    @Test void nestedHopsKeepTheChain() {
        String inner = "[[ChromeDriver: chrome on linux (3f1a)] -> css selector: .product_sort_container]";
        String outer = "[[" + inner + "] -> xpath: .//option[@value = \"za\"]]";
        assertEquals("css selector: .product_sort_container -> xpath: .//option[@value = \"za\"]", RlSelenium.elementDesc(outer));
        String third = "[[" + outer + "] -> tag name: span]";
        assertEquals("css selector: .product_sort_container -> xpath: .//option[@value = \"za\"] -> tag name: span", RlSelenium.elementDesc(third));
    }

    @Test void bracketsInsideTheLocatorSurvive() {
        assertEquals("xpath: //div[@id='a'][2]",
            RlSelenium.elementDesc("[[ChromeDriver: chrome on linux (3f1a)] -> xpath: //div[@id='a'][2]]"));
    }

    @Test void unknownShapeFallsBack() {
        assertEquals("element", RlSelenium.elementDesc("MyElement@1a2b"));
        assertEquals("element", RlSelenium.elementDesc("[unbalanced"));
    }
}
