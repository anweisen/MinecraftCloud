package net.anweisen.cloud.driver.player;

import net.anweisen.cloud.driver.CloudDriver;
import net.anweisen.cloud.driver.player.data.PlayerNetworkProxyConnection;
import net.anweisen.utilities.common.config.Document;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * @author anweisen | https://github.com/anweisen
 * @since 1.0
 */
public interface CloudOfflinePlayer {

	@Nonnull
	UUID getUniqueId();

	@Nonnull
	String getName();

	void setName(@Nonnull String name);

	@Nullable
	PlayerNetworkProxyConnection getLastNetworkConnection();

	long getFirstLoginTime();

	long getLastOnlineTime();

	void setLastOnlineTime(long lastOnlineTime);

	// TODO permission data

	@Nonnull
	Document getProperties();

	default void save() {
		CloudDriver.getInstance().getPlayerManager().saveOfflinePlayer(this);
	}

}