package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.DashboardStats;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.ScrapingJob;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import com.damKemon.dam.kemon.repository.ScrapingJobRepository;
import com.damKemon.dam.kemon.scraper.EcommerceScraper;
import com.damKemon.dam.kemon.scraper.ScraperEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final ScrapingJobRepository scrapingJobRepository;
    private final ScraperEngine scraperEngine;

    public DashboardController(ProductRepository productRepository,
                               ReviewRepository reviewRepository,
                               PriceHistoryRepository priceHistoryRepository,
                               ScrapingJobRepository scrapingJobRepository,
                               ScraperEngine scraperEngine) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.scrapingJobRepository = scrapingJobRepository;
        this.scraperEngine = scraperEngine;
    }

    @GetMapping("/stats")
    public ResponseEntity<DashboardStats> getStats() {
        long totalProducts = productRepository.count();
        long totalReviews = reviewRepository.count();
        long totalPricePoints = priceHistoryRepository.count();

        // Get recent searches from scraping jobs
        List<String> recentSearches = scrapingJobRepository.findTop10ByOrderByStartedAtDesc()
                .stream()
                .map(ScrapingJob::getQuery)
                .filter(Objects::nonNull)
                .distinct()
                .limit(5)
                .collect(Collectors.toList());

        // Build site stats
        List<DashboardStats.SiteStat> siteStats = scraperEngine.getScrapers().stream()
                .map(scraper -> {
                    long productCount = productRepository.findAll().stream()
                            .filter(p -> p.getPrices() != null &&
                                    p.getPrices().stream()
                                            .anyMatch(sp -> scraper.getSiteName().equals(sp.getSiteName())))
                            .count();

                    return DashboardStats.SiteStat.builder()
                            .siteName(scraper.getSiteName())
                            .productCount(productCount)
                            .status("active")
                            .build();
                })
                .collect(Collectors.toList());

        DashboardStats stats = DashboardStats.builder()
                .totalProducts(totalProducts)
                .totalSites(6)
                .totalReviews(totalReviews)
                .totalPricePoints(totalPricePoints)
                .recentSearches(recentSearches)
                .siteStats(siteStats)
                .build();

        return ResponseEntity.ok(stats);
    }
}
