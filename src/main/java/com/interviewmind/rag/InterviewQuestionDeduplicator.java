package com.interviewmind.rag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conservative text-level duplicate detection without model or embedding calls. */
public final class InterviewQuestionDeduplicator {
    private static final double SIMILARITY_THRESHOLD = 0.84;
    private InterviewQuestionDeduplicator() {}

    public static String normalize(String question) {
        if (question == null) return "";
        return Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s，。！？；：、“”‘’（）【】《》…—·]+", "");
    }

    public static boolean isDuplicate(String candidate, List<String> existing) {
        String normalized = normalize(candidate);
        if (normalized.isBlank()) return true;
        for (String value : existing) {
            String other = normalize(value);
            if (other.isBlank()) continue;
            if (normalized.equals(other) || similarity(normalized, other) >= SIMILARITY_THRESHOLD) return true;
        }
        return false;
    }

    public static <T> List<T> unique(List<T> items, java.util.function.Function<T, String> text) {
        List<T> result = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (T item : items) {
            String question = text.apply(item);
            if (!isDuplicate(question, seen)) {
                result.add(item);
                seen.add(question);
            }
        }
        return List.copyOf(result);
    }

    private static double similarity(String left, String right) {
        if (left.length() < 4 || right.length() < 4) return 0;
        List<String> a = bigrams(left);
        List<String> remaining = new ArrayList<>(a);
        List<String> b = bigrams(right);
        int intersection = 0;
        for (String token : b) {
            int index = remaining.indexOf(token);
            if (index >= 0) {
                intersection++;
                remaining.remove(index);
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 1 : (double) intersection / union;
    }

    private static List<String> bigrams(String text) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < text.length() - 1; i++) result.add(text.substring(i, i + 2));
        return result;
    }
}
