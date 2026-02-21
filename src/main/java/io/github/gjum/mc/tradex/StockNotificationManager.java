package io.github.gjum.mc.tradex;

import io.github.gjum.mc.tradex.model.Exchange;
import io.github.gjum.mc.tradex.model.Rule;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

import static io.github.gjum.mc.tradex.TradexMod.mod;

/**
 * Tracks per-session stock state for favourite exchanges and sends
 * one-time chat notifications on stock transitions:
 * <ul>
 *   <li>in-stock &rarr; out-of-stock  (red message)</li>
 *   <li>out-of-stock &rarr; restocked  (cyan message)</li>
 * </ul>
 * <p>
 * Each transition type is notified at most once per exchange per session.
 * Calling {@link #reset()} clears all session state (e.g. on disconnect).
 */
public class StockNotificationManager {

	/** What we know about one exchange during this session. */
	private static class ExchangeState {
		/** Last known stock value, or -1 if we have never seen a stock report. */
		int lastKnownStock = -1;
		boolean notifiedOutOfStock = false;
		boolean notifiedRestocked = false;
	}

	/** Keyed by the same string as {@link FavoritesManager#keyOf}. */
	private final Map<String, ExchangeState> states = new HashMap<>();

	/** Clear all session state (call on join / disconnect). */
	public void reset() {
		states.clear();
	}

	/**
	 * Called every time an exchange update arrives from chat
	 * (initial view <em>and</em> successful purchase).
	 * Only checks / notifies if the exchange is a favourite.
	 */
	public void onExchangeUpdate(@NotNull Exchange exchange) {
		FavoritesManager favorites = mod.favorites;
		if (!favorites.isFavorite(exchange)) return;

		String key = FavoritesManager.keyOf(exchange);
		ExchangeState state = states.computeIfAbsent(key, k -> new ExchangeState());

		int previousStock = state.lastKnownStock;
		int currentStock = exchange.stock;
		state.lastKnownStock = currentStock;

		// First time we see this exchange in this session – record state, no notification.
		if (previousStock == -1) return;

		// Transition: in-stock → out-of-stock
		if (previousStock > 0 && currentStock <= 0) {
			if (!state.notifiedOutOfStock) {
				state.notifiedOutOfStock = true;
				// Allow restocked notification on the next restock
				state.notifiedRestocked = false;
				notifyOutOfStock(exchange);
			}
		}

		// Transition: out-of-stock → restocked
		if (previousStock <= 0 && currentStock > 0) {
			if (!state.notifiedRestocked) {
				state.notifiedRestocked = true;
				// Allow out-of-stock notification on the next depletion
				state.notifiedOutOfStock = false;
				notifyRestocked(exchange, currentStock);
			}
		}
	}

	// ── notification helpers ───────────────────────────────────────────

	private void notifyOutOfStock(@NotNull Exchange exchange) {
		String name = getExchangeName(exchange);
		String pos = exchange.pos != null ? exchange.pos.toString() : "unknown";
		Component msg = Component.literal("[Tradex] " + name + " at " + pos + " is out of stock.")
				.withStyle(Style.EMPTY.withColor(ChatFormatting.RED));
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
