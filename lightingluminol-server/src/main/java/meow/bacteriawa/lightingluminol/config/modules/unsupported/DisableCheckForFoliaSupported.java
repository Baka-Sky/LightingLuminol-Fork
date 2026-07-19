package meow.bacteriawa.lightingluminol.config.modules.unsupported;

import meow.bacteriawa.lightingluminol.config.IConfigModule;
import meow.bacteriawa.lightingluminol.config.flags.ConfigClassInfo;
import meow.bacteriawa.lightingluminol.config.flags.ConfigInfo;
import meow.bacteriawa.lightingluminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.UNSUPPORTED, name = "disable_check_for_folia_supported")
public class DisableCheckForFoliaSupported implements IConfigModule {
    @ConfigInfo(name = "disable_for_paper", comments = {
            "Disable check for folia-supported for spigot/bukkit/paper plugin.",
            "ATTENTION: No support will be provided if you enabled this."
    })
    public static boolean disableForPaper = true;

    @ConfigInfo(name = "disable_for_leaves", comments = {
            "Disable check for folia-supported for leaves plugin.",
            "ATTENTION: No support will be provided if you enabled this."
    })
    public static boolean disableForLeaves = true;
}