package drop.events.swordsmp;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/**
 * Holds Drop Events custom gamerules.
 */
public final class DropEventRules {
	public static GameRule<Boolean> GUARD_VISIBLE;

	private DropEventRules() {
	}

	public static void register() {
		if (GUARD_VISIBLE == null) {
			GUARD_VISIBLE = GameRuleBuilder.forBoolean(false)
				.category(GameRuleCategory.MOBS)
				.buildAndRegister(Identifier.fromNamespaceAndPath(DropEvents.MOD_ID, "visible_guard"));
		}
	}
}