package net.anweisen.cloud.master.network.listener;

import net.anweisen.cloud.driver.CloudDriver;
import net.anweisen.cloud.driver.event.global.GlobalConfigUpdatedEvent;
import net.anweisen.cloud.driver.network.SocketChannel;
import net.anweisen.cloud.driver.network.packet.Packet;
import net.anweisen.cloud.driver.network.packet.PacketListener;
import net.anweisen.cloud.driver.network.packet.def.GlobalConfigPacket.GlobalConfigPacketType;
import net.anweisen.cloud.driver.network.packet.protocol.Buffer;

import javax.annotation.Nonnull;

/**
 * @author anweisen | https://github.com/anweisen
 * @since 1.0
 */
public class GlobalConfigListener implements PacketListener {

	@Override
	public void handlePacket(@Nonnull SocketChannel channel, @Nonnull Packet packet) throws Exception {
		GlobalConfigPacketType packetType = packet.getBuffer().readEnumConstant(GlobalConfigPacketType.class);
		switch (packetType) {
			case FETCH: {
				CloudDriver.getInstance().getLogger().debug("{} -> {}", CloudDriver.getInstance().getGlobalConfig().getRawData());
				channel.sendPacket(Packet.createResponseFor(packet, Buffer.create().writeDocument(CloudDriver.getInstance().getGlobalConfig().getRawData())));
				break;
			}
			case UPDATE: {
				CloudDriver.getInstance().getGlobalConfig().setRawData(packet.getBuffer().readDocument());
				CloudDriver.getInstance().getLogger().debug("{} -> {}", CloudDriver.getInstance().getGlobalConfig().getRawData());
				CloudDriver.getInstance().getEventManager().callEvent(new GlobalConfigUpdatedEvent());
				break;
			}
		}
	}
}