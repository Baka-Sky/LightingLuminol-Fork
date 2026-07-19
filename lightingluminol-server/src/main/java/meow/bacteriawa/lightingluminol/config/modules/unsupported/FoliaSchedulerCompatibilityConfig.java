package meow.bacteriawa.lightingluminol.config.modules.unsupported;

import meow.bacteriawa.lightingluminol.config.IConfigModule;
import meow.bacteriawa.lightingluminol.config.flags.ConfigClassInfo;
import meow.bacteriawa.lightingluminol.config.flags.ConfigInfo;
import meow.bacteriawa.lightingluminol.enums.EnumConfigCategory;

import java.util.List;

@ConfigClassInfo(category = EnumConfigCategory.UNSUPPORTED, name = "folia_scheduler_compatibility")
public class FoliaSchedulerCompatibilityConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = {
            "Automatically choose scheduler by the folia-supported flag parsed from each plugin jar.",
            "Plugins marked as folia-supported use the real Folia scheduler; other plugins use the Bukkit/global compatibility scheduler.",
            "This improves compatibility for some legacy plugins, but it does not make unsafe world/entity access region-thread safe."
    })
    public static boolean enabled = true;

    @ConfigInfo(name = "force_folia_scheduler_plugins", comments = {
            "Override auto detection: plugin names that should always use the real Folia scheduler."
    })
    public static List<String> forceFoliaSchedulerPlugins = List.of();

    @ConfigInfo(name = "force_bukkit_scheduler_plugins", comments = {
            "Override auto detection: plugin names that should always use the Bukkit/global compatibility scheduler."
    })
    public static List<String> forceBukkitSchedulerPlugins = List.of();
}