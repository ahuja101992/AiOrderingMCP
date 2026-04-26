package org.personal.mcp;

import com.squareup.square.Environment;
import com.squareup.square.SquareClient;
import com.squareup.square.api.CatalogApi;
import com.squareup.square.api.OrdersApi;
import com.squareup.square.models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin wrapper around the Square SDK for the MCP server's needs.
 * <p>
 * Two design notes worth understanding before reading the rest of this file:
 * <ol>
 *   <li>The Square SDK's typed-union objects (DietaryPreference, Ingredient) silently
 *       drop their value when the {@code type} discriminator isn't set on write. We
 *       saw this during sandbox testing — the value was stored as an empty string
 *       rather than rejected. On read we don't have the same problem, but we still
 *       need to look up values from whichever of {@code standard_name} or
 *       {@code custom_name} is populated.</li>
 *   <li>The menu cache here is intentionally simple: a single AtomicReference with
 *       a TTL. Multi-truck routing (where each instance serves many trucks) would
 *       need a per-truck cache map, but for phase 1 we serve one truck per
 *       process.</li>
 * </ol>
 */
public class SquareFoodTruckClient {

    private static final Logger log = LoggerFactory.getLogger(SquareFoodTruckClient.class);

    private static final long MENU_CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final Set<String> ACTIVE_FULFILLMENT_STATES =
            Set.of("PROPOSED", "RESERVED", "PREPARED");
    private static final int DEFAULT_PREP_MINUTES = 10;

    private final SquareClient client;
    private final String locationId;
    private final AtomicReference<CachedMenu> menuCache = new AtomicReference<>();

    public SquareFoodTruckClient(String accessToken, String locationId, Environment environment) {
        this.client = new SquareClient.Builder()
                .environment(environment)
                .accessToken(accessToken)
                .build();
        this.locationId = Objects.requireNonNull(locationId, "locationId");
    }

    /** Pull all FOOD_AND_BEV items, summarized for LLM consumption. Cached. */
    public List<MenuItem> getMenu(boolean forceRefresh) {
        CachedMenu cached = menuCache.get();
        long now = System.currentTimeMillis();
        if (!forceRefresh && cached != null && (now - cached.fetchedAtMillis) < MENU_CACHE_TTL_MILLIS) {
            return cached.items;
        }

        List<MenuItem> items = fetchAllFoodAndBevItems();
        menuCache.set(new CachedMenu(items, now));
        return items;
    }

    public Optional<MenuItem> getItemById(String itemId) {
        return getMenu(false).stream().filter(i -> i.id.equals(itemId)).findFirst();
    }

    public Optional<MenuItem> getItemByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String target = name.trim().toLowerCase(Locale.ROOT);
        return getMenu(false).stream()
                .filter(i -> i.name != null && i.name.toLowerCase(Locale.ROOT).equals(target))
                .findFirst();
    }

    /**
     * Estimate current wait time. Sums prep_time_duration across active PICKUP
     * fulfillments at this location, falling back to {@link #DEFAULT_PREP_MINUTES}
     * when an order doesn't have a prep time recorded.
     * <p>
     * This is approximate by design — Square does not expose a "queue depth"
     * primitive, so we count active orders and add up their declared prep times.
     */
    public WaitEstimate estimateWaitMinutes() {
        try {
            CatalogApi unused = client.getCatalogApi(); // touch APIs to fail fast on bad token
            OrdersApi ordersApi = client.getOrdersApi();

            SearchOrdersStateFilter stateFilter = new SearchOrdersStateFilter.Builder(
                    List.of("OPEN")
            ).build();

            SearchOrdersFilter filter = new SearchOrdersFilter.Builder()
                    .stateFilter(stateFilter)
                    .build();

            SearchOrdersSort sort = new SearchOrdersSort.Builder("CREATED_AT")
                    .sortOrder("DESC")
                    .build();

            SearchOrdersQuery query = new SearchOrdersQuery.Builder()
                    .filter(filter)
                    .sort(sort)
                    .build();

            SearchOrdersRequest request = new SearchOrdersRequest.Builder()
                    .locationIds(List.of(locationId))
                    .query(query)
                    .limit(100)
                    .build();

            SearchOrdersResponse response = ordersApi.searchOrders(request);
            List<Order> orders = response.getOrders() != null ? response.getOrders() : List.of();

            int activeOrders = 0;
            int totalMinutes = 0;
            for (Order order : orders) {
                List<Fulfillment> fulfillments = order.getFulfillments();
                if (fulfillments == null) continue;
                for (Fulfillment f : fulfillments) {
                    if (!"PICKUP".equals(f.getType())) continue;
                    if (!ACTIVE_FULFILLMENT_STATES.contains(f.getState())) continue;

                    activeOrders++;
                    FulfillmentPickupDetails details = f.getPickupDetails();
                    String prep = details != null ? details.getPrepTimeDuration() : null;
                    totalMinutes += parseIso8601Minutes(prep, DEFAULT_PREP_MINUTES);
                    break; // one fulfillment per order in current API
                }
            }

            return new WaitEstimate(activeOrders, activeOrders > 0 ? totalMinutes : 0);
        } catch (Exception e) {
            log.error("wait time estimation failed", e);
            throw new RuntimeException("Failed to estimate wait time: " + e.getMessage(), e);
        }
    }

    // ---------- internals ----------

    private List<MenuItem> fetchAllFoodAndBevItems() {
        List<MenuItem> result = new ArrayList<>();
        CatalogApi catalogApi = client.getCatalogApi();
        String cursor = null;

        try {
            do {
                SearchCatalogItemsRequest request = new SearchCatalogItemsRequest.Builder()
                        .productTypes(List.of("FOOD_AND_BEV"))
                        .enabledLocationIds(List.of(locationId))   // ← add this
                        .limit(100)
                        .cursor(cursor)
                        .build();

                SearchCatalogItemsResponse response = catalogApi.searchCatalogItems(request);
                List<CatalogObject> items = response.getItems();
                if (items != null) {
                    for (CatalogObject obj : items) {
                        MenuItem summary = summarize(obj);
                        // Skip items with no orderable variation; they can't be ordered anyway.
                        if (summary != null && summary.variationId != null) {
                            result.add(summary);
                        }
                    }
                }
                cursor = response.getCursor();
            } while (cursor != null && !cursor.isEmpty());
        } catch (Exception e) {
            log.error("catalog fetch failed", e);
            throw new RuntimeException("Failed to fetch menu: " + e.getMessage(), e);
        }
        return result;
    }

    private MenuItem summarize(CatalogObject obj) {
        CatalogItem itemData = obj.getItemData();
        if (itemData == null) return null;

        CatalogItemFoodAndBeverageDetails fnb = itemData.getFoodAndBeverageDetails();

        List<String> diets = new ArrayList<>();
        if (fnb != null && fnb.getDietaryPreferences() != null) {
            for (CatalogItemFoodAndBeverageDetailsDietaryPreference pref : fnb.getDietaryPreferences()) {
                String val = firstNonEmpty(pref.getStandardName(), pref.getCustomName());
                if (val != null) diets.add(val.toLowerCase(Locale.ROOT));
            }
        }

        List<String> ingredients = new ArrayList<>();
        if (fnb != null && fnb.getIngredients() != null) {
            for (CatalogItemFoodAndBeverageDetailsIngredient ing : fnb.getIngredients()) {
                String val = firstNonEmpty(ing.getCustomName(), ing.getStandardName());
                if (val != null) ingredients.add(val.toLowerCase(Locale.ROOT));
            }
        }

        String variationId = null;
        Money priceMoney = null;
        if (itemData.getVariations() != null && !itemData.getVariations().isEmpty()) {
            CatalogObject firstVar = itemData.getVariations().get(0);
            variationId = firstVar.getId();
            CatalogItemVariation variation = firstVar.getItemVariationData();
            if (variation != null) {
                priceMoney = variation.getPriceMoney();
            }
        }

        Integer calories = fnb != null ? fnb.getCalorieCount() : null;

        return new MenuItem(
                obj.getId(),
                itemData.getName(),
                itemData.getDescription(),
                calories,
                Collections.unmodifiableList(diets),
                Collections.unmodifiableList(ingredients),
                variationId,
                priceMoney
        );
    }

    private static String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    /** Parse a small subset of ISO 8601 durations (PT#H#M#S) into minutes. */
    static int parseIso8601Minutes(String duration, int fallback) {
        if (duration == null || !duration.startsWith("PT")) return fallback;
        String rest = duration.substring(2);
        int hours = 0, minutes = 0;
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (Character.isDigit(ch)) {
                num.append(ch);
            } else if (ch == 'H') {
                hours = num.length() == 0 ? 0 : Integer.parseInt(num.toString());
                num.setLength(0);
            } else if (ch == 'M') {
                minutes = num.length() == 0 ? 0 : Integer.parseInt(num.toString());
                num.setLength(0);
            } else if (ch == 'S') {
                num.setLength(0); // ignore seconds
            }
        }
        return hours * 60 + minutes;
    }

    // ---------- value types ----------

    /** Flat record of one menu item, exposed to the MCP layer. */
    public static final class MenuItem {
        public final String id;
        public final String name;
        public final String description;
        public final Integer calorieCount;
        public final List<String> dietaryPreferences;
        public final List<String> ingredients;
        public final String variationId;
        public final Money priceMoney;

        MenuItem(String id, String name, String description, Integer calorieCount,
                 List<String> dietaryPreferences, List<String> ingredients,
                 String variationId, Money priceMoney) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.calorieCount = calorieCount;
            this.dietaryPreferences = dietaryPreferences;
            this.ingredients = ingredients;
            this.variationId = variationId;
            this.priceMoney = priceMoney;
        }

        public boolean hasAllergenData() {
            return !ingredients.isEmpty() || !dietaryPreferences.isEmpty();
        }

        public String formattedPrice() {
            if (priceMoney == null || priceMoney.getAmount() == null) return null;
            String currency = priceMoney.getCurrency() != null ? priceMoney.getCurrency() : "USD";
            return String.format("%.2f %s", priceMoney.getAmount() / 100.0, currency);
        }
    }

    public static final class WaitEstimate {
        public final int activeOrdersAhead;
        public final int estimatedWaitMinutes;

        WaitEstimate(int activeOrdersAhead, int estimatedWaitMinutes) {
            this.activeOrdersAhead = activeOrdersAhead;
            this.estimatedWaitMinutes = estimatedWaitMinutes;
        }
    }

    private static final class CachedMenu {
        final List<MenuItem> items;
        final long fetchedAtMillis;

        CachedMenu(List<MenuItem> items, long fetchedAtMillis) {
            this.items = items;
            this.fetchedAtMillis = fetchedAtMillis;
        }
    }
}
