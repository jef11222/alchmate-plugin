package net.runelite.client.plugins.alchbridge;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AlchBridgePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AlchBridgePlugin.class);
		RuneLite.main(args);
	}
}
