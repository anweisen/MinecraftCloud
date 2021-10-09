package net.anweisen.cloud.wrapper;

import net.anweisen.cloud.driver.CloudDriver;
import net.anweisen.cloud.driver.DriverEnvironment;
import net.anweisen.cloud.driver.config.global.GlobalConfig;
import net.anweisen.cloud.driver.config.global.RemoteGlobalConfig;
import net.anweisen.cloud.driver.cord.CordManager;
import net.anweisen.cloud.driver.database.DatabaseManager;
import net.anweisen.cloud.driver.database.remote.RemoteDatabaseManager;
import net.anweisen.cloud.driver.network.SocketClient;
import net.anweisen.cloud.driver.network.handler.SocketChannelClientHandler;
import net.anweisen.cloud.driver.network.listener.*;
import net.anweisen.cloud.driver.network.netty.client.NettySocketClient;
import net.anweisen.cloud.driver.network.object.HostAndPort;
import net.anweisen.cloud.driver.network.packet.PacketConstants;
import net.anweisen.cloud.driver.network.packet.PacketListenerRegistry;
import net.anweisen.cloud.driver.network.packet.def.AuthenticationPacket;
import net.anweisen.cloud.driver.network.packet.def.AuthenticationPacket.AuthenticationPayload;
import net.anweisen.cloud.driver.network.packet.def.ServiceUpdateSelfInfoPacket;
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
import net.anweisen.cloud.driver.service.specific.ServiceControlState;
import net.anweisen.cloud.driver.service.specific.ServiceInfo;
import net.anweisen.cloud.driver.service.specific.ServiceState;
import net.anweisen.cloud.driver.translate.TranslationManager;
import net.anweisen.cloud.driver.translate.defaults.RemoteTranslationManager;
import net.anweisen.cloud.wrapper.config.WrapperConfig;
import net.anweisen.cloud.wrapper.event.service.ServiceInfoConfigureEvent;
import net.anweisen.utilities.common.collection.WrappedException;
import net.anweisen.utilities.common.logging.handler.HandledLogger;
import net.anweisen.utilities.common.misc.ReflectionUtils;

import javax.annotation.Nonnull;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * @author anweisen | https://github.com/anweisen
 * @since 1.0
 *
 * @see DriverEnvironment#WRAPPER
 */
public final class CloudWrapper extends CloudDriver {

	private final Path workDirectory = Paths.get("");

	private final WrapperConfig config = new WrapperConfig();

	private final List<String> commandlineArguments;
	private final Instrumentation instrumentation;

	private final NodeManager nodeManager;
	private final DatabaseManager databaseManager;
	private final ServiceManager serviceManager;
	private final ServiceConfigManager serviceConfigManager;
	private final ServiceFactory serviceFactory;
	private final PlayerManager playerManager;
	private final GlobalConfig globalConfig;
	private final TranslationManager translationManager;

	private final Thread mainThread = Thread.currentThread();
	private Thread applicationThread;
	private ClassLoader applicationClassLoader;
	private final ClassLoader bootClassLoader = getClass().getClassLoader();
	private Path applicationFile;

	private SocketClient socketClient;
	private ServiceInfo serviceInfo;

	CloudWrapper(@Nonnull HandledLogger logger, @Nonnull List<String> commandlineArguments, @Nonnull Instrumentation instrumentation) {
		super(logger, DriverEnvironment.WRAPPER);
		setInstance(this);

		this.commandlineArguments = commandlineArguments;
		this.instrumentation = instrumentation;

		globalConfig = new RemoteGlobalConfig();
		nodeManager = new RemoteNodeManager();
		databaseManager = new RemoteDatabaseManager();
		serviceManager = new RemoteServiceManager();
		serviceConfigManager = new RemoteServiceConfigManager();
		serviceFactory = new RemoteServiceFactory();
		playerManager = new RemotePlayerManager();
		permissionManager = new RemotePermissionManager();
		translationManager = new RemoteTranslationManager();
	}

	public synchronized void start() throws Exception {
		logger.info("Launching the CloudWrapper..");
		logger.extended("Localhost IP: {}", HostAndPort.localhost());

		logger.debug("Loading wrapper configuration..");
		config.load();

		socketClient = new NettySocketClient(SocketChannelClientHandler::new);

		loadNetworkListeners(socketClient.getListenerRegistry());
		connectAndAwaitAuthentication();

		// Retrieve current service info
		serviceInfo = serviceManager.getServiceInfoByUniqueId(config.getServiceUniqueId());

		try {
			startApplication();
		} catch (Exception ex) {
			logger.error("Unable to start application", ex);
			// TODO stop
		}

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

			logger.debug("Waiting for authentication response..");
			condition.await();

		} finally {
			lock.unlock();
		}

		if (!listener.getResult())
			throw new IllegalStateException("Network authentication failed: " + listener.getMessage());

		listener.readConfigProperties();
		logger.debug("Network authentication was successful");

	}

	private void sendAuthentication() {
		logger.debug("Sending authentication to master.. Service: '{}'", config.getServiceUniqueId());
		socketClient.sendPacket(new AuthenticationPacket(AuthenticationPayload.SERVICE, config.getIdentity(), config.getServiceUniqueId(), buffer -> {}));
	}

	private void loadNetworkListeners(@Nonnull PacketListenerRegistry registry) {
		logger.info("Registering network listeners..");

		registry.addListener(PacketConstants.NODE_INFO_PUBLISH_CHANNEL, new NodePublishListener());
		registry.addListener(PacketConstants.SERVICE_INFO_PUBLISH_CHANNEL, new ServicePublishListener());
		registry.addListener(PacketConstants.PLAYER_EVENT_CHANNEL, new PlayerEventListener());
		registry.addListener(PacketConstants.PLAYER_REMOTE_MANAGER_CHANNEL, new PlayerRemoteManagerListener());
		registry.addListener(PacketConstants.GLOBAL_CONFIG_CHANNEL, new GlobalConfigUpdateListener());
	}

	public synchronized void startApplication() throws Exception {

		String applicationFileName = commandlineArguments.remove(0);
		logger.debug("Using '{}' as application file..", applicationFileName);

		applicationFile = Paths.get(applicationFileName);
		if (Files.notExists(applicationFile)) throw new IllegalStateException("Application file " + applicationFileName + " does not exist");
		URL applicationFileUrl = applicationFile.toUri().toURL();

		// Inject the classpath into current class loader
		// This eliminates the need of creating a new class loader and setting it as the system class loader which does not work properly with java16
		// TODO just use the instrumentation?
		try {
			ClassLoader loader = this.getClass().getClassLoader();
			Class<?> loaderClass = loader.getClass();
			Field ucpField = ReflectionUtils.getInheritedPrivateField(loaderClass, "ucp");
			ucpField.setAccessible(true);
			Object ucp = ucpField.get(loader);

			Class<?> ucpClass = ucp.getClass();
			Method addUrlMethod = ucpClass.getDeclaredMethod("addURL", URL.class);
			addUrlMethod.setAccessible(true);
			addUrlMethod.invoke(ucp, applicationFileUrl);

			applicationClassLoader = loader;
		} catch (Throwable exInjectClassPath) {
			logger.error("Unable to inject application file to ucp (class path) of current class loader. Fallbacking to an URLClassLoader.", exInjectClassPath);
			applicationClassLoader = new URLClassLoader(new URL[] { applicationFileUrl }, this.getClass().getClassLoader());

			// https://stackoverflow.com/questions/5380275/replacement-system-classloader-for-classes-in-jars-containing-jars
			// Replace system class loader with created URLCLassLoader
			try {
				Field loaderField = ClassLoader.class.getDeclaredField("scl");
				loaderField.setAccessible(true);
				loaderField.set(null, applicationClassLoader);

				// field in pre java9 that indicated whether a system class loader is set
				Field setField = ClassLoader.class.getDeclaredField("sclSet");
				setField.setAccessible(true);
				setField.set(null, true);
			} catch (Throwable exReplaceSystemLoader) {
			}
		}

		Attributes manifestAttributes = getManifestAttributes(applicationFile);

		String mainClassName = manifestAttributes.getValue("Main-Class");
		String agentClassName = manifestAttributes.getValue("Launcher-Agent-Class");
		logger.debug("Found attributes main:{} agent:{}", mainClassName, agentClassName);

		if (agentClassName != null) {
			try {
				Class<?> agentClass = Class.forName(agentClassName, true, applicationClassLoader);
				Method agentMethod = agentClass.getMethod("agentmain", String.class, Instrumentation.class);
				logger.trace("Invoking agentmain method..");
				agentMethod.invoke(null, null, instrumentation);
				logger.trace("Successfully invoked agentmain method");
			} catch (ClassNotFoundException ex) {
			} catch (Throwable ex) {
				logger.error("Unable to execute agentmain", ex);
			}
		}

		Class<?> mainClass = Class.forName(mainClassName, true, applicationClassLoader);
		Method mainMethod = mainClass.getMethod("main", String[].class);

		applicationThread = new Thread(() -> {
			try {
				logger.info("Starting application thread..");
				mainMethod.invoke(
					null,
					new Object[] { new String[0] }
				);
			} catch (Exception ex) {
				logger.error("Unable to start application..", ex);
			}
		}, "Application-Thread");

		applicationThread.setContextClassLoader(applicationClassLoader);
		applicationThread.start();

		executor.scheduleAtFixedRate(this::updateServiceInfo, 0, ServiceInfo.PUBLISH_INTERVAL, TimeUnit.MILLISECONDS);

	}

	@Override
	public synchronized void shutdown() throws Exception {

		logger.info("Closing socket connection..");
		socketClient.closeChannels();
		logger.info("Shutting down socket client..");
		socketClient.shutdown();

		shutdownDriver();

	}

	public void updateServiceInfo() {
		ServiceInfo serviceInfo = this.createServiceInfo();
		eventManager.callEvent(new ServiceInfoConfigureEvent(serviceInfo));
		socketClient.sendPacket(new ServiceUpdateSelfInfoPacket(serviceInfo));
		logger.trace("Updating service info to {}", serviceInfo);
		this.serviceInfo = serviceInfo;
	}

	private ServiceInfo createServiceInfo() {
		return new ServiceInfo(
			serviceInfo.getUniqueId(),
			serviceInfo.getDockerContainerId(),
			serviceInfo.getTaskName(),
			serviceInfo.getServiceNumber(),
			serviceInfo.getEnvironment(),
			ServiceState.RUNNING,
			ServiceControlState.NONE,
			serviceInfo.getNodeName(),
			serviceInfo.getNodeAddress(),
			serviceInfo.getPort(),
			serviceInfo.isPermanent(),
			serviceInfo.getProperties()
		);
	}

	@Nonnull
	private Attributes getManifestAttributes(@Nonnull Path applicationFile) {
		try (JarFile jarFile = new JarFile(applicationFile.toFile())) {
			Manifest manifest = jarFile.getManifest();
			if (manifest == null) throw new IllegalStateException("Manifest is null");
			return manifest.getMainAttributes();
		} catch (Exception ex) {
			throw new WrappedException("Unable to extract manifest attributes from jarfile", ex);
		}
	}

	@Nonnull
	@Override
	public WrapperConfig getConfig() {
		return config;
	}

	@Nonnull
	@Override
	public GlobalConfig getGlobalConfig() {
		return globalConfig;
	}

	@Nonnull
	public Instrumentation getInstrumentation() {
		return instrumentation;
	}

	@Nonnull
	public ClassLoader getApplicationClassLoader() {
		return applicationClassLoader;
	}

	@Nonnull
	public ClassLoader getBootClassLoader() {
		return bootClassLoader;
	}

	@Nonnull
	public Thread getApplicationThread() {
		return applicationThread;
	}

	@Nonnull
	public Thread getMainThread() {
		return mainThread;
	}

	@Nonnull
	public Path getApplicationFile() {
		return applicationFile;
	}

	@Nonnull
	public Path getWorkDirectory() {
		return workDirectory;
	}

	@Nonnull
	@Override
	public SocketClient getSocketComponent() {
		return socketClient;
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
	@Override
	public TranslationManager getTranslationManager() {
		return translationManager;
	}

	@Nonnull
	@Override
	public String getComponentName() {
		return serviceInfo.getName();
	}

	@Nonnull
	public ServiceInfo getServiceInfo() {
		return serviceInfo;
	}

	private static CloudWrapper instance;

	public static CloudWrapper getInstance() {
		if (instance == null)
			instance = (CloudWrapper) CloudDriver.getInstance();

		return instance;
	}
}
