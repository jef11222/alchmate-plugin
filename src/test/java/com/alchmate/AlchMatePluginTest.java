package com.alchmate;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AlchMatePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AlchMatePlugin.class);
		RuneLite.main(args);
	}
}
