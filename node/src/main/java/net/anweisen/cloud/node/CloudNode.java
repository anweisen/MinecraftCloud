package net.anweisen.cloud.node;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Network.Ipam;
import com.github.dockerjava.api.model.Network.Ipam.Config;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.jaxrs.JerseyDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import net.anweisen.cloud.base.CloudBase;
import net.anweisen.cloud.base.module.ModuleController;
import net.anweisen.cloud.base.node.NodeCycleData;
import net.anweisen.cloud.driver.CloudDriver;
import net.anweisen.cloud.driver.DriverEnvironment;
import net.anweisen.cloud.driver.config.global.GlobalConfig;
import net.anweisen.cloud.driver.config.global.RemoteGlobalConfig;
import net.anweisen.cloud.driver.console.Console;
import net.anweisen.cloud.driver.console.HeaderPrinter;
import net.anweisen.cloud.driver.cord.CordManager;
import net.anweisen.cloud.driver.database.DatabaseManager;
import net.anweisen.cloud.driver.database.remote.RemoteDatabaseManager;
import net.anweisen.cloud.driver.network.object.HostAndPort;
import net.anweisen.cloud.driver.network.SocketChannel;
import net.anweisen.cloud.driver.network.SocketClient;
import net.anweisen.cloud.driver.network.handler.SocketChannelClientHandler;
import net.anweisen.cloud.driver.network.listener.*;
import net.anweisen.cloud.driver.network.netty.client.NettySocketClient;
import net.anweisen.cloud.driver.network.packet.Packet;
import net.anweisen.cloud.driver.network.packet.PacketConstants;
import net.anweisen.cloud.driver.network.packet.PacketListenerRegistry;
import net.anweisen.cloud.driver.network.packet.def.AuthenticationPacket;
import net.anweisen.cloud.driver.network.packet.def.AuthenticationPacket.AuthenticationType;
import net.anweisen.cloud.driver.network.packet.def.ModuleSystemPacket;
import net.anweisen.cloud.driver.network.packet.def.ModuleSystemPacket.ModuleSystemRequestType;
import net.anweisen.cloud.driver.network.packet.protocol.Buffer;
import net.anweisen.cloud.driver.node.NodeManager;
import net.anweisen.cloud.driver.node.RemoteNodeManager;
import net.anweisen.cloud.driver.player.PlayerManager;
import net.anweisen.cloud.driver.player.defaults.RemotePlayerManager;
import net.anweisen.cloud.driver.player.permission.impl.RemotePermissionManager;
import net.anweisen.cloud.driver.service.RemoteServiceFactory;
import net.anweisen.cloud.driver.service.RemoteServiceManager;
import net.anweisen.cloud.driver.service.ServiceFactory;
import net.anweisen.cloud.driver.service.ServiceManager;
import net.anweisen.cloud.driver.service.config.RemoteServiceConfigManager;
import net.anweisen.cloud.driver.service.config.ServiceConfigManager;
import net.anweisen.cloud.node.config.NodeConfig;
import net.anweisen.cloud.node.docker.DockerEventListener;
import net.anweisen.cloud.node.docker.DockerServiceActor;
import net.anweisen.cloud.node.network.listener.ServiceControlListener;
import net.anweisen.utilities.common.logging.handler.HandledLogger;
import net.anweisen.utilities.common.misc.FileUtils;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author anweisen | https://github.com/anweisen
 * @since 1.0
 *
 * @see DriverEnvironment#NODE
 */
public final class CloudNode extends CloudBase {

	private final NodeConfig config = new NodeConfig();

	private final DatabaseManager databaseManager;
	private final ServiceConfigManager serviceConfigManager;
	private final ServiceManager serviceManager;
	private final ServiceFactory serviceFactory;
	private final NodeManager nodeManager;
	private final PlayerManager playerManager;
	private final GlobalConfig globalConfig;
	private final DockerServiceActor serviceActor;

	private SocketClient socketClient;
	private DockerClient dockerClient;

	CloudNode(@Nonnull HandledLogger logger, @Nonnull Console console) {
		super(logger, console, DriverEnvironment.NODE);
		setInstance(this);

		Path wrapperOrigin = Paths.get("wrapper.jar");
		if (!Files.exists(wrapperOrigin)) throw new IllegalStateException("Missing wrapper.jar");

		nodeManager = new RemoteNodeManager();
		databaseManager = new RemoteDatabaseManager();
		serviceConfigManager = new RemoteServiceConfigManager();
		serviceManager = new RemoteServiceManager();
		serviceFactory = new RemoteServiceFactory();
		permissionManager = new RemotePermissionManager();
		playerManager = new RemotePlayerManager();
		serviceActor = new DockerServiceActor();
		globalConfig = new RemoteGlobalConfig();

		HeaderPrinter.printHeader(console, this);
	}

	public synchronized void start() throws Exception {
		logger.info("Launching the CloudNode..");
		logger.extended("Localhost IP: {}", HostAndPort.localhost());

		logger.info("Loading cloud configuration..");
		config.load();
		console.setScreenName(getComponentName());

		logger.info("Connecting to docker daemon..");
		initDocker();

		socketClient = new NettySocketClient(SocketChannelClientHandler::new);

		loadNetworkListeners(socketClient.getListenerRegistry());
		connectAndAwaitAuthentication();

		moduleManager.setModulesDirectory(getTempDirectory().resolve("modules"));
		initModules();

		pullJavaImages();

		executor.scheduleAtFixedRate(this::publishDataCycle, 1_000, NodeCycleData.PUBLISH_INTERVAL, TimeUnit.MILLISECONDS);

	}

	private void initDocker() {

		DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
			.withDockerHost(this.config.getDockerHost())
			.build();

		DockerHttpClient httpClient = new JerseyDockerHttpClient.Builder()
			.dockerHost(config.getDockerHost())
			.sslConfig(config.getSSLConfig())
			.build();

		dockerClient = DockerClientBuilder.getInstance(config)
			.withDockerHttpClient(httpClient)
			.build();

		dockerClient.pingCmd().exec();
		logger.info("Successfully pinged docker daemon");

		dockerClient.eventsCmd()
			.withEventTypeFilter("container")
			.exec(new DockerEventListener());
		logger.debug("Registered docker container event listener");
	}

	private void connectAndAwaitAuthentication() throws InterruptedException {

		AuthenticationResponseListener listener;
		Lock lock = new ReentrantLock();
		try {

			lock.lock();

			Condition condition = lock.newCondition();
			listener = new AuthenticationResponseListener(lock, condition);

			logger.info("Connecting to master socket on {}..", config.getMasterAddress());
			socketClient.getListenerRegistry().addListener(PacketConstants.AUTH_CHANNEL, listener);
			socketClient.connect(config.getMasterAddress());

			sendAuthentication();

			logger.info("Waiting for authentication response..");
			condition.await();

		} finally {
			lock.unlock();
		}

		if (!listener.getResult())
			throw new IllegalStateException("Network authentication failed: " + listener.getMessage());

		listener.readConfigProperties();
		logger.info("Network authentication was successful");

	}

	private void sendAuthentication() {
		logger.info("Sending authentication to master..");

		Config ipamConfig = dockerClient.listNetworksCmd()
			.withNameFilter(config.getDockerNetworkMode())
			.exec().stream().findFirst()
			.map(Network::getIpam)
			.map(Ipam::getConfig)
			.map(configs -> configs.isEmpty() ? null : configs.get(0))
			.orElseThrow(() -> new IllegalStateException("Unable to retrieve ipam config of '" + config.getDockerNetworkMode() + "'"));

		socketClient.sendPacket(new AuthenticationPacket(AuthenticationType.NODE, config.getIdentity(), config.getNodeName(), buffer -> {
			buffer.writeString(ipamConfig.getGateway()).writeString(ipamConfig.getSubnet());
		}));
	}

	private void loadNetworkListeners(@Nonnull PacketListenerRegistry registry) {
		logger.info("Registering network listeners..");

		registry.addListener(PacketConstants.NODE_INFO_PUBLISH_CHANNEL, new NodeInfoPublishListener());
		registry.addListener(PacketConstants.SERVICE_INFO_PUBLISH_CHANNEL, new ServiceInfoPublishListener());
		registry.addListener(PacketConstants.SERVICE_CONTROL_CHANNEL, new ServiceControlListener());
		registry.addListener(PacketConstants.PLAYER_EVENT_CHANNEL, new PlayerEventListener());
		registry.addListener(PacketConstants.PLAYER_REMOTE_MANAGER_CHANNEL, new PlayerRemoteManagerListener());
		registry.addListener(PacketConstants.GLOBAL_CONFIG_CHANNEL, new GlobalConfigUpdateListener());
	}

	private void pullJavaImages() {
		logger.info("Pulling docker java images..");
		Set<Integer> javaVersions = new TreeSet<>();
		serviceConfigManager.getTasks().forEach(task -> javaVersions.add(task.getJavaVersion()));
		for (int javaVersion : javaVersions) {
			String image = "openjdk:" + javaVersion;
			try {
				logger.info("Pulling image '{}' for java-{}..", image, javaVersion);
				dockerClient.pullImageCmd(image).start().awaitCompletion();
				logger.info("Successfully pulled image '{}'", image);
			} catch (Exception ex) {
				logger.error("Unable to pull image '{}'", image, ex);
			}
		}
	}

	private void initModules() throws Exception {
		logger.info("Requesting & downloading modules..");
		SocketChannel channel = socketClient.getFirstChannel();

		String[] names = channel.sendPacketQuery(new ModuleSystemPacket(ModuleSystemRequestType.GET_MODULES)).getBuffer().readStringArray();
		for (int i = 0; i < names.length; i++) {
			logger.info("Downloading module {}..", names[i]);
			InputStream input = channel.sendChunkedPacketQuery(new ModuleSystemPacket(ModuleSystemRequestType.GET_MODULE_JAR, i)).getBeforeTimeout(10, TimeUnit.SECONDS).getInputStream();
			Path file = moduleManager.getModulesDirectory().resolve(names[i]);
			FileUtils.copy(input, Files.newOutputStream(file, StandardOpenOption.CREATE));
			input.close();
		}

		moduleManager.resolveModules();

		for (int i = 0; i < names.length; i++) {
			InputStream input = channel.sendChunkedPacketQuery(new ModuleSystemPacket(ModuleSystemRequestType.GET_MODULE_DATA_FOLDER, i)).getBeforeTimeout(10, TimeUnit.SECONDS).getInputStream();
			ModuleController module = moduleManager.getModules().get(i);
			FileUtils.extract(input, module.getDataFolder());
			input.close();
		}

		moduleManager.loadModules();
		moduleManager.enableModules();
	}

	private void publishDataCycle() {
		socketClient.sendPacket(new Packet(PacketConstants.NODE_DATA_CYCLE, Buffer.create().writeObject(NodeCycleData.current())));
	}

	@Override
	public synchronized void shutdown() throws Exception {

		logger.info("Shutting down..");

		logger.info("Closing socket connection..");
		socketClient.closeChannels();
		logger.info("Shutting down socket client..");
		socketClient.shutdown();

		shutdownBase();
		shutdownDriver();

	}

	@Nonnull
	@Override
	public SocketClient getSocketComponent() {
		return socketClient;
	}

	@Nonnull
	public DockerClient getDockerClient() {
		return dockerClient;
	}

	@Nonnull
	@Override
	public DatabaseManager getDatabaseManager() {
		return databaseManager;
	}

	@Nonnull
	@Override
	public ServiceConfigManager getServiceConfigManager() {
		return serviceConfigManager;
	}

	@Nonnull
	@Override
	public ServiceFactory getServiceFactory() {
		return serviceFactory;
	}

	@Nonnull
	@Override
	public ServiceManager getServiceManager() {
		return serviceManager;
	}

	@Nonnull
	@Override
	public NodeManager getNodeManager() {
		return nodeManager;
	}

	@Nonnull
	@Override
	public CordManager getCordManager() {
		return null;
	}

	@Nonnull
	@Override
	public PlayerManager getPlayerManager() {
		return playerManager;
	}

	@Nonnull
	public DockerServiceActor getServiceActor() {
		return serviceActor;
	}

	@Nonnull
	@Override
	public String getComponentName() {
		return config.getNodeName();
	}

	@Nonnull
	@Override
	public GlobalConfig getGlobalConfig() {
		return globalConfig;
	}

	@Nonnull
	@Override
	public NodeConfig getConfig() {
		return config;
	}

	private static CloudNode instance;

	public static CloudNode getInstance() {
		if (instance == null)
			instance = (CloudNode) CloudDriver.getInstance();

		return instance;
	}

}
