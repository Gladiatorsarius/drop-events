package drop.events.swordsmp;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Builds Xaero's ten-field chat share token, sent alone as the whole message. */
public final class DropEventWaypoint {
	private DropEventWaypoint() {}

	private static final String NAME_PREFIX = "Dropevent:";
	// Xaero 26.6.2 rejects shared names outside 1..32 characters (decoded).
	private static final int MAX_NAME_LENGTH = 32;

	public static Component waypointMessage(BlockPos pos, ResourceKey<Level> dimension, String itemName) {
		// Token-only: Xaero hides any chat line containing its token and replaces
		// it with its own share message, so extra text would only reach players
		// without Xaero.
		return Component.literal(waypointToken(pos, dimension, itemName));
	}

	static String waypointToken(BlockPos pos, ResourceKey<Level> dimension, String itemName) {
		return "xaero-waypoint:" + xaeroField(waypointName(itemName)) + ":D:" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ()
			+ ":6:false:0:Internal-" + xaeroDimension(dimension.identifier()) + "-waypoints";
	}

	/** "Dropevent:" plus the item display name, cut to Xaero's 32 character limit. */
	static String waypointName(String itemName) {
		StringBuilder name = new StringBuilder(NAME_PREFIX);
		if (itemName != null) {
			// Xaero strips section-sign codes from the whole line; controls would break it.
			String clean = itemName.replace("\u00a7", "").replaceAll("\\p{Cntrl}+", " ").strip();
			int end = Math.min(clean.length(), MAX_NAME_LENGTH - NAME_PREFIX.length());
			if (end > 0 && end < clean.length() && Character.isHighSurrogate(clean.charAt(end - 1))) end--;
			name.append(clean, 0, end);
		}
		return name.toString().stripTrailing();
	}

	/** Mirrors Xaero 26.6.2 WaypointSharingHandler: colon, then removeFormatting order. */
	static String xaeroField(String value) {
		return value.replace(":", "^col^").replace("-", "^min^").replace("_", "-").replace("*", "^ast^");
	}

	static String xaeroDimension(Identifier dimension) {
		if (dimension.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
			switch (dimension.getPath()) {
				case "overworld": return "overworld";
				case "the_nether": return "the-nether";
				case "the_end": return "the-end";
				default: break;
			}
		}
		// Match Xaero 26.6.2's directory codec, then its share escaping order.
		String path = dimension.getPath().replace('/', '%');
		int trailingDots = path.length();
		while (trailingDots > 0 && path.charAt(trailingDots - 1) == '.') trailingDots--;
		path = path.substring(0, trailingDots) + ",".repeat(path.length() - trailingDots);
		return ("dim%" + dimension.getNamespace() + "$" + path)
			.replace("-", "^min^").replace("_", "-").replace("*", "^ast^");
	}
}
