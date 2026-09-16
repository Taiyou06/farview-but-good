package net.gensokyoreimagined.farview;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.skills.placeholders.PlaceholderString;
import io.lumine.mythic.core.skills.placeholders.PlaceholderContext;
import io.lumine.mythic.core.skills.placeholders.segments.types.ResolvedPlaceholderSegment;
import io.lumine.mythic.core.skills.placeholders.types.EntityScopedPlaceholder;
import io.lumine.mythic.core.skills.placeholders.types.GenericPlaceholderTypes.StringPlaceholder;
import io.lumine.mythic.core.utils.annotations.MythicPlaceholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

@MythicPlaceholder(placeholder = "farview", usedPlaceholderArguments = 1)
public final class FarViewMythicPlaceholder extends EntityScopedPlaceholder<String> implements StringPlaceholder {
    private final ResolvedPlaceholderSegment<PlaceholderString> key;

    public FarViewMythicPlaceholder(EntityScopedPlaceholderArguments arguments) {
        super(arguments);
        this.key = getPlaceholderString(0);
    }

    @Override
    public String applyToScope(PlaceholderContext context) {
        AbstractEntity entity = getEntity.get(context);
        if (entity == null || !entity.isPlayer()) return "";
        String name = key.getOrDefault(context, PlaceholderString::get, "");
        String value = FarViewPlaceholders.resolve(JavaPlugin.getPlugin(FarViewPlugin.class), (Player) entity.getBukkitEntity(), name);
        return value == null ? "" : value;
    }
}
