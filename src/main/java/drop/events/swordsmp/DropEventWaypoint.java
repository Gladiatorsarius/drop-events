package drop.events.swordsmp;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Builds Xaero's ten-field chat share token, which must end the message. */
public final class DropEventWaypoint {
	private DropEventWaypoint() {}

	public static Component waypointMessage(ItemStack stack, BlockPos pos, ResourceKey<Level> dimension) {
		Component name = stack.getHoverName();
		// Xaero strips section-sign formatting pairs before scanning for its FIRST
		// token. Check that normalized text so formatting cannot hide a prefix.
		String normalizedName = name.getString().replaceAll("§.", "");
		if (normalizedName.contains("xaero-waypoint:")) {
			name = Component.literal(normalizedName.replace("§", "").replace("xaero-waypoint:", "xaero-waypoint："));
		}
		return Component.literal("Drop event of ").append(name)
			.append(Component.literal(" at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " | "))
			.append(Component.literal(waypointToken(pos, dimension)));
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
