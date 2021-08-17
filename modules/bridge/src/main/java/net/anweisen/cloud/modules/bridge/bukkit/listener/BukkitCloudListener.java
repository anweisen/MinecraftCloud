package net.anweisen.cloud.modules.bridge.bukkit.listener;

import net.anweisen.cloud.driver.event.EventListener;
import net.anweisen.cloud.driver.service.specific.ServiceProperties;
import net.anweisen.cloud.modules.bridge.helper.BridgeHelper;
import net.anweisen.cloud.modules.bridge.helper.data.MinecraftPlayerInfo;
import net.anweisen.cloud.modules.bridge.helper.data.PluginInfo;
import net.anweisen.cloud.wrapper.event.service.ServiceInfoConfigureEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * @author anweisen | https://github.com/anweisen
 * @since 1.0
 */
public class BukkitCloudListener {

	@EventListener
	public void onInfoConfigure(@Nonnull ServiceInfoConfigureEvent event) {
		BridgeHelper.setOnlineCount(Bukkit.getOnlinePlayers().size());
		event.getServiceInfo().getProperties()
			.set(ServiceProperties.MAX_PLAYER_COUNT, BridgeHelper.getMaxPlayers())
			.set(ServiceProperties.ONLINE_COUNT, Bukkit.getOnlinePlayers().size())
			.set(ServiceProperties.PLAYERS, Bukkit.getOnlinePlayers().stream().map(player -> {
				return new MinecraftPlayerInfo(player.getName(), player.getUniqueId());
			}).collect(Collectors.toList()))
			.set(ServiceProperties.PLUGINS, Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(Plugin::getDescription).map(plugin -> {
				return new PluginInfo(plugin.getName(), plugin.getAuthors().toArray(new String[0]), plugin.getVersion(), plugin.getMain(), plugin.getDescription());
			}).collect(Collectors.toList()))
		;
	}

}