package drop.events.swordsmp;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Builds Xaero's ten-field chat share token, sent alone as the whole message. */
public final class DropEventWaypoint {
	private DropEventWaypoint() {}

	public static Component waypointMessage(BlockPos pos, ResourceKey<Level> dimension) {
		// Token-only: Xaero hides any chat line containing its token and replaces
		// it with its own share message, so extra text would only reach players
		// without Xaero. The item name stays visible in the bossbar title.
		return Component.literal(waypointToken(pos, dimension));
	}

	static String waypointToken(BlockPos pos, ResourceKey<Level> dimension) {
		// Fixed, short name avoids delimiters and unbounded client translations.
		return "xaero-waypoint:Drop event:D:" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ()
			+ ":6:false:0:Internal-" + xaeroDimension(dimension.identifier()) + "-waypoints";
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
