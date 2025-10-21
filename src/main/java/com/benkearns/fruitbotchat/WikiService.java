package com.benkearns.fruitbotchat;

import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class WikiService {
    private static final String WIKI_BASE_URL = "https://fruitservers.net/wiki";
    
    private final OkHttpClient client;
    private final Map<String, CachedResponse> cache = new HashMap<>();
    private static final long CACHE_DURATION = TimeUnit.HOURS.toMillis(1);
    private String wikiContent = null;
    private long lastWikiLoad = 0;
    private final Map<String, String> wikiSections = new HashMap<>();
    private final Map<String, String> allPages = new HashMap<>();
    private final Set<String> crawledUrls = new HashSet<>();
    
    public WikiService() {
        this.client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    }
    
    public String searchAndSummarize(String query) {
        try {
            CachedResponse cached = cache.get(query.toLowerCase());
            if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_DURATION) {
                return cached.response;
            }
            
            loadWikiContent();
            if (wikiContent == null) {
                return null;
            }
            
            String summary = findRelevantContent(query);
            if (summary != null && !summary.trim().isEmpty()) {
                cache.put(query.toLowerCase(), new CachedResponse(summary, System.currentTimeMillis()));
                return summary;
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    private void loadWikiContent() throws IOException {
        if (wikiContent != null && System.currentTimeMillis() - lastWikiLoad < CACHE_DURATION) {
            return;
        }
        
        crawledUrls.clear();
        allPages.clear();
        
        crawlWikiPages(WIKI_BASE_URL, 0);
        
        StringBuilder allContent = new StringBuilder();
        for (Map.Entry<String, String> page : allPages.entrySet()) {
            allContent.append("PAGE: ").append(page.getKey()).append("\n");
            allContent.append(page.getValue()).append("\n\n");
        }
        
        wikiContent = allContent.toString();
        lastWikiLoad = System.currentTimeMillis();
        parseWikiSections();
    }
    
    private void crawlWikiPages(String url, int depth) throws IOException {
        if (depth > 2 || crawledUrls.contains(url) || crawledUrls.size() > 50) {
            return;
        }
        
        crawledUrls.add(url);
        
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return;
            
            String html = response.body().string();
            Document doc = Jsoup.parse(html, url);
            
            doc.select("script, style, .navbox, .infobox, .mw-editsection, nav, header, footer").remove();
            
            Elements contentElements = doc.select(".mw-parser-output, .content, #content, main");
            if (contentElements.isEmpty()) {
                contentElements = doc.select("body");
            }
            
            StringBuilder pageContent = new StringBuilder();
            for (Element element : contentElements) {
                extractTextContent(element, pageContent);
            }
            
            String pageName = extractPageName(url);
            if (pageContent.length() > 100) {
                allPages.put(pageName, pageContent.toString());
            }
            
            if (depth < 2) {
                Elements links = doc.select("a[href]");
                for (Element link : links) {
                    String href = link.absUrl("href");
                    if (isValidWikiLink(href)) {
                        crawlWikiPages(href, depth + 1);
                    }
                }
            }
        } catch (Exception e) {
        }
    }
    
    private String extractPageName(String url) {
        if (url.equals(WIKI_BASE_URL)) {
            return "Home";
        }
        
        String[] parts = url.split("/");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            return lastPart.replace("_", " ").replace("%20", " ");
        }
        return "Unknown";
    }
    
    private boolean isValidWikiLink(String href) {
        if (href == null || !href.startsWith(WIKI_BASE_URL)) {
            return false;
        }
        
        if (href.contains("#") || href.contains("?") || href.contains("action=")) {
            return false;
        }
        
        if (href.contains("Special:") || href.contains("File:") || href.contains("Category:")) {
            return false;
        }
        
        return !crawledUrls.contains(href);
    }
    
    private void extractTextContent(Element element, StringBuilder content) {
        Elements paragraphs = element.select("p, li, h1, h2, h3, h4, h5, h6, .mw-headline");
        for (Element p : paragraphs) {
            String text = p.text().trim();
            if (text.length() > 10 && !text.startsWith("Edit") && !text.startsWith("From ")) {
                content.append(text).append("\n");
            }
        }
    }
    
    private void parseWikiSections() {
        if (wikiContent == null) return;
        
        wikiSections.clear();
        String[] lines = wikiContent.split("\n");
        String currentSection = "general";
        StringBuilder sectionContent = new StringBuilder();
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            if (isHeading(line)) {
                if (sectionContent.length() > 0) {
                    wikiSections.put(currentSection.toLowerCase(), sectionContent.toString());
                }
                currentSection = line.replaceAll("[^a-zA-Z0-9\\s]", "").trim();
                sectionContent = new StringBuilder();
            } else {
                sectionContent.append(line).append(" ");
            }
        }
        
        if (sectionContent.length() > 0) {
            wikiSections.put(currentSection.toLowerCase(), sectionContent.toString());
        }
    }
    
    private boolean isHeading(String line) {
        return line.length() < 100 && (line.matches(".*[A-Z].*") && !line.contains(".") && !line.contains(","));
    }
    
    private String findRelevantContent(String query) {
        if (wikiContent == null) return null;
        
        String queryLower = query.toLowerCase();
        String[] queryWords = queryLower.split("\\s+");
        
        String bestMatch = null;
        int bestScore = 0;
        
        for (Map.Entry<String, String> section : wikiSections.entrySet()) {
            String sectionName = section.getKey();
            String sectionContent = section.getValue().toLowerCase();
            
            int score = 0;
            
            if (sectionName.contains(queryLower) || sectionContent.contains(queryLower)) {
                score += 10;
            }
            
            for (String word : queryWords) {
                if (word.length() > 2) {
                    if (sectionName.contains(word)) score += 5;
                    if (sectionContent.contains(word)) score += 2;
                }
            }
            
            if (score > bestScore) {
                bestScore = score;
                bestMatch = section.getValue();
            }
        }
        
        if (bestMatch != null && bestScore > 3) {
            return summarizeContent(bestMatch, query);
        }
        
        return searchInFullContent(query);
    }
    
    private String searchInFullContent(String query) {
        if (wikiContent == null) return null;
        
        String[] sentences = wikiContent.split("[.!?]\\s+");
        String queryLower = query.toLowerCase();
        String[] queryWords = queryLower.split("\\s+");
        
        List<String> relevantSentences = new ArrayList<>();
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() < 20 || sentence.length() > 300) continue;
            
            String sentenceLower = sentence.toLowerCase();
            int matches = 0;
            
            if (sentenceLower.contains(queryLower)) {
                matches += 10;
            } else {
                for (String word : queryWords) {
                    if (word.length() > 2 && sentenceLower.contains(word)) {
                        matches++;
                    }
                }
            }
            
            if (matches >= Math.min(2, queryWords.length)) {
                relevantSentences.add(sentence);
                if (relevantSentences.size() >= 3) break;
            }
        }
        
        if (relevantSentences.isEmpty()) return null;
        
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(2, relevantSentences.size()); i++) {
            String sentence = relevantSentences.get(i).trim();
            if (!sentence.endsWith(".") && !sentence.endsWith("!") && !sentence.endsWith("?")) {
                sentence += ".";
            }
            result.append(sentence);
            if (i < relevantSentences.size() - 1 && i < 1) {
                result.append(" ");
            }
        }
        
        return result.toString().trim();
    }
    
    private String summarizeContent(String content, String query) {
        if (content == null || content.trim().isEmpty()) return null;
        
        String[] sentences = content.split("\\. ");
        List<String> relevantSentences = new ArrayList<>();
        String queryLower = query.toLowerCase();
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() < 20 || sentence.length() > 200) continue;
            
            String sentenceLower = sentence.toLowerCase();
            if (isRelevantSentence(sentenceLower, queryLower)) {
                relevantSentences.add(sentence);
                if (relevantSentences.size() >= 2) break;
            }
        }
        
        if (relevantSentences.isEmpty() && sentences.length > 0) {
            String firstSentence = sentences[0].trim();
            if (firstSentence.length() >= 20 && firstSentence.length() <= 200) {
                relevantSentences.add(firstSentence);
            }
        }
        
        if (relevantSentences.isEmpty()) return null;
        
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < Math.min(2, relevantSentences.size()); i++) {
            String sentence = relevantSentences.get(i);
            if (!sentence.endsWith(".") && !sentence.endsWith("!") && !sentence.endsWith("?")) {
                sentence += ".";
            }
            summary.append(sentence);
            if (i < relevantSentences.size() - 1 && i < 1) {
                summary.append(" ");
            }
        }
        
        String result = summary.toString().trim();
        return result.length() > 10 ? result : null;
    }
    
    private boolean isRelevantSentence(String sentence, String query) {
        if (sentence.contains(query)) return true;
        
        String[] queryWords = query.split("\\s+");
        int matches = 0;
        for (String word : queryWords) {
            if (word.length() > 2 && sentence.contains(word)) {
                matches++;
            }
        }
        
        return matches >= Math.min(2, queryWords.length);
    }
    
    public void shutdown() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
    
    private static class CachedResponse {
        final String response;
        final long timestamp;
        
        CachedResponse(String response, long timestamp) {
            this.response = response;
            this.timestamp = timestamp;
        }
    }
}
