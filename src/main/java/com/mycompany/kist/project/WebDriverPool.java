/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.kist.project;

/**
 *
 * @author yetun
 */
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

public class WebDriverPool {

    private final BlockingQueue<WebDriver> pool;

    public WebDriverPool(int poolSize) {
        pool = new LinkedBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            pool.add(createWebDriver());
        }
    }

    private WebDriver createWebDriver() {
        System.setProperty("webdriver.chrome.driver", "C://chromedriver-win64/chromedriver.exe");
        ChromeOptions options = new ChromeOptions();
        options.addArguments("headless");
        options.addArguments("disable-gpu");
        options.addArguments("no-sandbox");
        // Ekstra optimizasyonlar
        options.addArguments("disable-images");
        options.addArguments("disable-extensions");
        options.addArguments("disable-popup-blocking");
        options.addArguments("disable-dev-shm-usage");
        options.addArguments("disable-infobars");
        options.addArguments("--blink-settings=imagesEnabled=false");
        return new ChromeDriver(options);
    }

    public WebDriver borrowDriver() throws InterruptedException {
        return pool.take();
    }

    public void returnDriver(WebDriver driver) {
        pool.offer(driver);
    }

    public void shutdown() {
        for (WebDriver driver : pool) {
            driver.quit();
        }
    }
}
