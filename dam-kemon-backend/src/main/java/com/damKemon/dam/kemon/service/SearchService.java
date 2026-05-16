package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.intelligence.QueryIntent;
import com.damKemon.dam.kemon.intelligence.ResultValidator.ScoredResult;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.scraper.EcommerceScraper;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.damKemon.dam.kemon.scraper.ScraperEngine;
import com.damKemon.dam.kemon.scraper.ScraperEngine.EngineSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final ScraperEngine scraperEngine;
    private final ProductRepository productRepository;

    public SearchService(ScraperEngine scraperEngine, ProductRepository productRepository) {
        this.scraperEngine = scraperEngine;
        this.productRepository = productRepository;
    }

    /**
     * Cached for 10 minutes (configured in application.yml cache spec).
     * Key = normalized query (lower-cased, trimmed, whitespace collapsed) so
     * "iPhone 15", " IPHONE  15 " all hit the same cache entry.
     *
     * The `unless` clause avoids caching empty/error responses so a transient
     * scraping failure doesn't poison the cache for 10 minutes.
     */
    @Cacheable(
        value = "search",
        key = "#query == null ? '' : T(java.util.regex.Pattern).compile('\\\\s+').matcher(#query.trim()).replaceAll(' ').toLowerCase()",
        condition = "#query != null && #query.trim().length() >= 2",
        unless = "#result == null || #result.totalResults == null || #result.totalResults == 0"
    )
    public SearchResponse search(String query) {
        log.info("Searching for: {} (cache MISS)", query);
        EngineSearchResult engineResult = scraperEngine.searchAll(query);
        QueryIntent intent = engineResult.intent;
        Map<String, List<ScoredResult>> bySite = engineResult.bySite;

        // dedupe scored results into Products
        Map<String, Product> deduplicatedProducts = new LinkedHashMap<>();
        Map<String, Double> productScore = new HashMap<>();

        for (Map.Entry<String, List<ScoredResult>> entry : bySite.entrySet()) {
            String siteName = entry.getKey();
            EcommerceScraper scraperRef = scraperEngine.getScrapers().stream()
                    .filter(s -> s.getSiteName().equals(siteName)).findFirst().orElse(null);
            String siteSlug = scraperRef != null ? scraperRef.getSiteSlug() : siteName.toLowerCase();

            for (ScoredResult scored : entry.getValue()) {
                ScrapedProduct sp = scored.product;
                if (sp.getName() == null || sp.getName().isBlank() || sp.getPrice() == null) continue;

                String normalized = normalizeName(sp.getName());
                Product existing = findSimilarProduct(deduplicatedProducts, normalized);

                SitePrice sitePrice = SitePrice.builder()
                        .siteName(siteName)
                        .siteSlug(siteSlug)
                        .productUrl(sp.getProductUrl())   // <-- real URL preserved
                        .price(sp.getPrice())
                        .originalPrice(sp.getOriginalPrice())
                        .discount(discount(sp.getOriginalPrice(), sp.getPrice()))
                        .currency("BDT")
                        .inStock(sp.getInStock())
                        .rating(sp.getRating())
                        .reviewCount(sp.getReviewCount())
                        .lastUpdated(LocalDateTime.now())
                        .build();

                if (existing != null) {
                    // avoid duplicating same site twice
                    existing.getPrices().removeIf(p -> Objects.equals(p.getSiteName(), siteName));
                    existing.getPrices().add(sitePrice);
                    productScore.merge(normalized, scored.score, Math::max);
                    updateAggregateFields(existing);
                } else {
                    Product product = Product.builder()
                            .name(sp.getName())
                            .slug(generateSlug(sp.getName()))
                            .category(intent.primaryCategory().getLabel())
                            .imageUrl(sp.getImageUrl())
                            .prices(new ArrayList<>(List.of(sitePrice)))
                            .lastScraped(LocalDateTime.now())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    updateAggregateFields(product);
                    deduplicatedProducts.put(normalized, product);
                    productScore.put(normalized, scored.score);
                }
            }
        }

        // sort by score desc (best matches first)
        List<Map.Entry<String, Product>> ranked = new ArrayList<>(deduplicatedProducts.entrySet());
        ranked.sort((a, b) -> Double.compare(
                productScore.getOrDefault(b.getKey(), 0.0),
                productScore.getOrDefault(a.getKey(), 0.0)));

        // best-effort persist; even if Mongo is unreachable, return results
        List<Product> savedProducts = new ArrayList<>();
        for (Map.Entry<String, Product> e : ranked) {
            Product p = e.getValue();
            try { savedProducts.add(productRepository.save(p)); }
            catch (Exception ex) {
                log.debug("Save failed for {}: {}", p.getName(), ex.getMessage());
                savedProducts.add(p);
            }
        }

        List<String> sitesSearched = new ArrayList<>(bySite.keySet());
        List<String> sitesSkipped = scraperEngine.getScrapers().stream()
                .map(EcommerceScraper::getSiteName)
                .filter(name -> !sitesSearched.contains(name))
                .collect(Collectors.toList());

        return SearchResponse.builder()
                .query(query)
                .products(savedProducts)
                .totalResults(savedProducts.size())
                .sitesSearched(sitesSearched)
                .sitesSkipped(sitesSkipped)
                .detectedCategory(intent.primaryCategory().getLabel())
                .categories(intent.getCategories().stream().map(c -> c.getLabel()).collect(Collectors.toList()))
                .brands(intent.getBrands())
                .confidence(intent.getConfidence())
                .build();
    }

    // ---------- helpers ----------
    private String normalizeName(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Product findSimilarProduct(Map<String, Product> products, String normalizedName) {
        if (products.containsKey(normalizedName)) return products.get(normalizedName);
        for (Map.Entry<String, Product> e : products.entrySet()) {
            if (areSimilar(e.getKey(), normalizedName)) return e.getValue();
        }
        return null;
    }

    private boolean areSimilar(String a, String b) {
        Set<String> wa = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> wb = new HashSet<>(Arrays.asList(b.split("\\s+")));
        Set<String> inter = new HashSet<>(wa); inter.retainAll(wb);
        Set<String> union = new HashSet<>(wa); union.addAll(wb);
        if (union.isEmpty()) return false;
        return (double) inter.size() / union.size() > 0.7;
    }

    private void updateAggregateFields(Product product) {
        List<SitePrice> prices = product.getPrices();
        if (prices == null || prices.isEmpty()) return;
        OptionalDouble minP = prices.stream().filter(p -> p.getPrice() != null).mapToDouble(SitePrice::getPrice).min();
        OptionalDouble maxP = prices.stream().filter(p -> p.getPrice() != null).mapToDouble(SitePrice::getPrice).max();
        OptionalDouble avgR = prices.stream().filter(p -> p.getRating() != null).mapToDouble(SitePrice::getRating).average();
        int total = prices.stream().filter(p -> p.getReviewCount() != null).mapToInt(SitePrice::getReviewCount).sum();

        product.setLowestPrice(minP.isPresent() ? minP.getAsDouble() : null);
        product.setHighestPrice(maxP.isPresent() ? maxP.getAsDouble() : null);
        product.setAverageRating(avgR.isPresent() ? Math.round(avgR.getAsDouble() * 10.0) / 10.0 : null);
        product.setTotalReviews(total > 0 ? total : null);
    }

    private Double discount(Double original, Double current) {
        if (original == null || current == null || original <= 0 || original <= current) return null;
        return Math.round((original - current) / original * 100.0 * 10.0) / 10.0;
    }

    private String generateSlug(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
