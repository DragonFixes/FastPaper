package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.ConfigModule;

public class ModifyPlayerData extends ConfigModule {

    public String basePath() {
        return ConfigCategory.GAMEPLAY.basePath() + ".modify-player-data";
    }

    public static boolean enabled = false;

    @Override
    public void onLoaded() {
        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled, globalConfig.pickStringRegionBased("""
                Lets /data and /execute store change player data.
                Vanilla refuses with "Unable to modify player data".
                Supported tags match the Modify Player Data mod.""",
            """
                允许 /data 和 /execute store 修改玩家数据。
                原版会提示"无法修改玩家数据"。
                支持的标签与 Modify Player Data 模组一致。"""));
    }
}
