package com.mcagent;

import com.mcagent.config.ConfigManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCAgentMod implements ModInitializer {
	public static final String MOD_ID = "mcagent";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ConfigManager.load();
		LOGGER.info("MCAgent loaded. Configure with /mcagent, give goals with @mcagent <goal>");
	}
}
