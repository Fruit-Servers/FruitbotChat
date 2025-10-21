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
    private static final String SEARCH_URL = WIKI_BASE_URL + "/api.php?action=opensearch&search=%s&limit=3&namespace=0&format=json";
    private static final String PAGE_URL = WIKI_BASE_URL + "/api.php?action=parse&page=%s&format=json&prop=text&section=0";
    
    private final OkHttpClient client;
    private final Map<String, CachedResponse> cache = new HashMap<>();
    private static final long CACHE_DURATION = TimeUnit.HOURS.toMillis(1);
    
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
            
            List<String> searchResults = searchWiki(query);
            if (searchResults.isEmpty()) {
                return null;
            }
            
            String bestMatch = findBestMatch(query, searchResults);
            if (bestMatch == null) {
                return null;
            }
            
            String content = getPageContent(bestMatch);
            if (content == null) {
                return null;
            }
            
            String summary = summarizeContent(content, query);
            if (summary != null) {
                cache.put(query.toLowerCase(), new CachedResponse(summary, System.currentTimeMillis()));
            }
            
            return summary;
        } catch (Exception e) {
            return null;
        }
    }
    
    private List<String> searchWiki(String query) throws IOException {
        String url = String.format(SEARCH_URL, query.replace(" ", "%20"));
        Request request = new Request.Builder().url(url).build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return Collections.emptyList();
            
            String json = response.body().string();
            return parseSearchResults(json);
        }
    }
    
    private List<String> parseSearchResults(String json) {
        List<String> results = new ArrayList<>();
        try {
            json = json.trim();
            if (json.startsWith("[") && json.endsWith("]")) {
                String[] parts = json.substring(1, json.length() - 1).split(",(?=\\[)");
                if (parts.length >= 2) {
                    String titlesSection = parts[1].trim();
                    if (titlesSection.startsWith("[") && titlesSection.endsWith("]")) {
                        String[] titles = titlesSection.substring(1, titlesSection.length() - 1).split(",");
                        for (String title : titles) {
                            String cleaned = title.trim().replaceAll("^\"|\"$", "");
                            if (!cleaned.isEmpty()) {
                                results.add(cleaned);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return results;
    }
    
    private String findBestMatch(String query, List<String> results) {
        String queryLower = query.toLowerCase();
        String bestMatch = null;
        int bestScore = 0;
        
        for (String result : results) {
            int score = calculateRelevanceScore(queryLower, result.toLowerCase());
            if (score > bestScore) {
                bestScore = score;
                bestMatch = result;
            }
        }
        
        return bestScore > 0 ? bestMatch : (results.isEmpty() ? null : results.get(0));
    }
    
    private int calculateRelevanceScore(String query, String title) {
        int score = 0;
        if (title.contains(query)) score += 10;
        
        String[] queryWords = query.split("\\s+");
        for (String word : queryWords) {
            if (word.length() > 2 && title.contains(word)) {
                score += 3;
            }
        }
        
        return score;
    }
    
    private String getPageContent(String pageTitle) throws IOException {
        String url = String.format(PAGE_URL, pageTitle.replace(" ", "%20"));
        Request request = new Request.Builder().url(url).build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            
            String json = response.body().string();
            return extractContentFromJson(json);
        }
    }
    
    private String extractContentFromJson(String json) {
        try {
            int textStart = json.indexOf("\"text\":{\"*\":\"");
            if (textStart == -1) return null;
            
            textStart += 13;
            int textEnd = json.indexOf("\"}}", textStart);
            if (textEnd == -1) return null;
            
            String htmlContent = json.substring(textStart, textEnd);
            htmlContent = htmlContent.replace("\\\"", "\"")
                                   .replace("\\n", "\n")
                                   .replace("\\/", "/");
            
            Document doc = Jsoup.parse(htmlContent);
            
            doc.select("script, style, .navbox, .infobox, .mw-editsection").remove();
            
            Elements paragraphs = doc.select("p, li");
            StringBuilder content = new StringBuilder();
            
            for (Element p : paragraphs) {
                String text = p.text().trim();
                if (text.length() > 20 && !text.startsWith("This page") && !text.startsWith("From ")) {
                    content.append(text).append(" ");
                    if (content.length() > 1000) break;
                }
            }
            
            return content.toString().trim();
        } catch (Exception e) {
            return null;
        }
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
