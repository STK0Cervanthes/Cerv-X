package io.github.gjum.mc.tradex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.gjum.mc.tradex.model.Exchange;
import io.github.gjum.mc.tradex.model.Rule;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static io.github.gjum.mc.tradex.TradexMod.LOG;
import static io.github.gjum.mc.tradex.TradexMod.mod;

/**
 * Tracks stock state for favourite exchanges and sends
 * chat notifications on stock transitions:
 * <ul>
 *   <li>in-stock &rarr; out-of-stock  (magenta message)</li>
 *   <li>out-of-stock &rarr; restocked  (cyan message)</li>
 * </ul>
 * <p>
 * Last-known stock values are persisted to disk so that transitions
 * can be detected across game sessions.
 * Each transition type is notified at most once per session per exchange.
 */
public class StockNotificationManager {

	private static final Path STOCK_STATE_FILE = Path.of("tradex-stock-state.json");
	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<HashMap<String, Integer>>() {}.getType();

	/** Persisted: last known stock value per exchange key. */
	private final Map<String, Integer> persistedStock = new HashMap<>();

	/** Session-only: whether we already notified for each state this session. */
	private static class SessionFlags {
		boolean notifiedOutOfStock = false;
		boolean notifiedRestocked = false;
	}

	private final Map<String, SessionFlags> sessionFlags = new HashMap<>();

	public StockNotificationManager() {
		loadPersistedStock();
	}

	/**
	 * Reset session notification flags on join/disconnect.
	 * Persisted stock data is kept so the first poll after login
	 * can detect transitions from the previous session.
	 */
	public void reset() {
		sessionFlags.clear();
	}

	/**
	 * Called every time an exchange update arrives (chat, search results, poll).
	 * Only checks / notifies if the exchange is a favourite.
	 */
	public void onExchangeUpdate(@NotNull Exchange exchange) {
		FavoritesManager favorites = mod.favorites;
		if (!favorites.isFavorite(exchange)) return;

		String key = FavoritesManager.keyOf(exchange);
		int currentStock = exchange.stock;

		// Look up previous stock from persisted state (survives across sessions)
		Integer previousStock = persistedStock.get(key);

		// Update persisted stock and save
		persistedStock.put(key, currentStock);
		savePersistedStock();

		// First time ever seeing this exchange — record only, no notification
		if (previousStock == null) return;

		SessionFlags flags = sessionFlags.computeIfAbsent(key, k -> new SessionFlags());

		// Transition: in-stock → out-of-stock
		if (previousStock > 0 && currentStock <= 0) {
			if (!flags.notifiedOutOfStock) {
				flags.notifiedOutOfStock = true;
				flags.notifiedRestocked = false;
				notifyOutOfStock(exchange);
			}
		}

		// Transition: out-of-stock → restocked
		if (previousStock <= 0 && currentStock > 0) {
			if (!flags.notifiedRestocked) {
				flags.notifiedRestocked = true;
				flags.notifiedOutOfStock = false;
				notifyRestocked(exchange, currentStock);
			}
		}
	}

	// ── persistence ────────────────────────────────────────────────────

	private void loadPersistedStock() {
		try {
			if (Files.exists(STOCK_STATE_FILE)) {
				String json = Files.readString(STOCK_STATE_FILE);
				Map<String, Integer> loaded = gson.fromJson(json, MAP_TYPE);
				if (loaded != null) {
					persistedStock.putAll(loaded);
				}
			}
		} catch (Exception e) {
			LOG.warn("Failed to load stock state", e);
		}
	}

	private void savePersistedStock() {
		try {
			Files.writeString(STOCK_STATE_FILE, gson.toJson(persistedStock));
		} catch (IOException e) {
			LOG.warn("Failed to save stock state", e);
		}
	}

	// ── notification helpers ───────────────────────────────────────────

	private void notifyOutOfStock(@NotNull Exchange exchange) {
		String name = getExchangeName(exchange);
		String pos = exchange.pos != null ? exchange.pos.toString() : "unknown";
		Component msg = Component.literal("[Tradex] " + name + " at " + pos + " is out of stock.")
				.withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE));
		Utils.showChat(msg);
	}

	private void notifyRestocked(@NotNull Exchange exchange, int currentStock) {
		String name = getExchangeName(exchange);
		String pos = exchange.pos != null ? exchange.pos.toString() : "unknown";
		Component msg = Component.literal("[Tradex] " + name + " at " + pos
						+ " has been restocked with " + currentStock + " exchange" + (currentStock != 1 ? "s" : "") + ".")
				.withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA));
		Utils.showChat(msg);
	}

	// ── exchange naming (mirrors SearchGui logic) ──────────────────────

	@NotNull
	static String getExchangeName(@NotNull Exchange exchange) {
		String input = formatRuleShort(exchange.input);
		if (exchange.output == null) return input + " donation";
		String output = formatRuleShort(exchange.output);
		return input + " -> " + output;
	}

	@NotNull
	private static String formatRuleShort(@Nullable Rule rule) {
		if (rule == null) return "?";
		String mat = rule.material;
		if (rule.potionName != null) mat = rule.potionName;
		if (rule.customName != null) mat = '"' + rule.customName + '"';
		if (rule.bookGeneration != null) mat = " (" + rule.bookGeneration + ')';
		return rule.count + " " + mat;
	}
}
