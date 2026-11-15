package dev.ucplab.merchant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reads the {@code --catalog} file: {@code {"items":[{"id","title","price_minor","stock"}]}}. Every refusal has a fixed
 * message that names the rule (and an item's position), never the file's path or its text.
 */
final class CatalogFile {
    static final int MAX_ITEMS = 50;
    static final int MAX_TITLE = 200;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Set<String> ITEM_KEYS = Set.of("id", "title", "price_minor", "stock");

    private CatalogFile() {}

    static List<Catalog.Item> load(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw refuse("the catalog file cannot be read");
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(text);
        } catch (RuntimeException e) {
            throw refuse("the catalog file is not a JSON object");
        }
        if (!root.keySet().equals(Set.of("items")) || !(root.get("items") instanceof List<?> raw)) {
            throw refuse("the catalog file must hold exactly one key, items, a list");
        }
        if (raw.isEmpty() || raw.size() > MAX_ITEMS) {
            throw refuse("the catalog file must list 1 to " + MAX_ITEMS + " items");
        }
        List<Catalog.Item> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            Catalog.Item item = item(raw.get(i), i + 1);
            if (!ids.add(item.id())) throw refuse("item " + (i + 1) + " repeats an earlier item id");
            items.add(item);
        }
        return List.copyOf(items);
    }

    private static Catalog.Item item(Object raw, int position) {
        if (!(raw instanceof Map<?, ?> m) || !m.keySet().equals(ITEM_KEYS)) {
            throw refuse("item " + position + " must have exactly id, title, price_minor and stock");
        }
        if (!(m.get("id") instanceof String id) || !ID.matcher(id).matches()) {
            throw refuse("item " + position + ": id must match [A-Za-z0-9_-]{1,100}");
        }
        if (!(m.get("title") instanceof String title) || title.isBlank() || title.length() > MAX_TITLE) {
            throw refuse("item " + position + ": title must be 1 to " + MAX_TITLE + " characters");
        }
        if (!(m.get("price_minor") instanceof Long price) || price < 1) {
            throw refuse("item " + position + ": price_minor must be an integer of at least 1");
        }
        if (!(m.get("stock") instanceof Long stock) || stock < 0 || stock > Integer.MAX_VALUE) {
            throw refuse("item " + position + ": stock must be an integer of at least 0");
        }
        return new Catalog.Item(id, title, price, stock.intValue());
    }

    private static IllegalArgumentException refuse(String reason) {
        return new IllegalArgumentException("catalog file refused: " + reason);
    }
}
