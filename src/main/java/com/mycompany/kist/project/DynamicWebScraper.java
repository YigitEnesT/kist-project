/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.kist.project;

/**
 *
 * @author yetun
 */
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class DynamicWebScraper {

    public static void main(String[] args) {
        // EdgeDriver'ın yolu
        System.setProperty("webdriver.edge.driver", "C:/edgedriver_win64/msedgedriver.exe"); // Burayı güncelleyin

        // WebDriver nesnesini oluştur
        WebDriver driver = new EdgeDriver();

        // URL'ye git
        driver.get("https://www.toplukatalog.gov.tr/?tokat_library%5B%5D=857&keyword=1*&tokat_search_field=1&cwid=2#alt"); // Dinamik veri çekmek istediğiniz sayfa

        // Dinamik içerik için bekle
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        WebElement dynamicElement = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("dynamicElementId"))); // Dinamik elemanı bul

        // Veriyi al
        String dynamicData = dynamicElement.getText();

        // Veriyi yazdır
        System.out.println("Çekilen Dinamik Veri: " + dynamicData);

        // Tarayıcıyı kapat
        driver.quit();
    }
}