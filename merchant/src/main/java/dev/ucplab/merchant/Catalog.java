package dev.ucplab.merchant;

import java.util.*;

/** In-memory product catalog with stock. Mutable on purpose: the lab changes stock and price to cause failures. */
final class Catalog {
    record Item(String id, String title, long price, int stock) {}

    private final Map<String, Item> items = new LinkedHashMap<>();

    Catalog() { reset(); }

    synchronized void reset() {
        items.clear();
        put(new Item("item_123", "Red T-Shirt", 2500, 100));
        put(new Item("item_456", "Blue Mug", 1200, 50));
        put(new Item("item_789", "Limited Sneakers", 12000, 3));
    }

    private void put(Item i) { items.put(i.id(), i); }

    synchronized Optional<Item> find(String id) { return Optional.ofNullable(items.get(id)); }

    synchronized List<Item> all() { return new ArrayList<>(items.values()); }

    synchronized boolean setStock(String id, int stock) {
        Item i = items.get(id);
        if (i == null) return false;
        put(new Item(i.id(), i.title(), i.price(), stock));
        return true;
    }

    synchronized boolean setPrice(String id, long price) {
        Item i = items.get(id);
        if (i == null) return false;
        put(new Item(i.id(), i.title(), price, i.stock()));
        return true;
    }

    /** Take stock for every item or for none of them. */
    synchronized boolean takeAll(Map<String, Integer> need) {
        for (var e : need.entrySet()) {
            Item i = items.get(e.getKey());
            if (i == null || i.stock() < e.getValue()) return false;
        }
        for (var e : need.entrySet()) {
            Item i = items.get(e.getKey());
            put(new Item(i.id(), i.title(), i.price(), i.stock() - e.getValue()));
        }
        return true;
    }

    synchronized void giveBack(Map<String, Integer> need) {
        for (var e : need.entrySet()) {
            Item i = items.get(e.getKey());
            if (i != null) put(new Item(i.id(), i.title(), i.price(), i.stock() + e.getValue()));
        }
    }
}
