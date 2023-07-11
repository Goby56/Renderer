package me.x150.testmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class TestMod implements ModInitializer {
	/**
	 * Runs the mod initializer.
	 */

	public static final String MOD_ID = "testmod";
	@Override
	public void onInitialize() {

	}

	public static Path getAssetsDir() {
		return FabricLoader.getInstance().getModContainer(MOD_ID).get().findPath("assets").get().resolve(MOD_ID);
	}
}
