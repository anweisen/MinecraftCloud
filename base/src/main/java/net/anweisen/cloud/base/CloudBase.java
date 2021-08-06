package net.anweisen.cloud.base;

import net.anweisen.cloud.driver.CloudDriver;
import net.anweisen.cloud.driver.DriverEnvironment;
import net.anweisen.cloud.driver.console.Console;
import net.anweisen.utilities.common.logging.ILogger;
import net.anweisen.utilities.common.misc.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author anweisen | https://github.com/anweisen
 * @since 1.0
 */
public abstract class CloudBase extends CloudDriver {

	protected final Console console;

	protected final Path tempDirectory;

	{
		tempDirectory = Paths.get(System.getProperty("cloud.temp", "temp"));
		FileUtils.createDirectoryReported(tempDirectory);
		FileUtils.setTempDirectory(tempDirectory);
	}

	public CloudBase(@Nonnull ILogger logger, @Nonnull Console console, @Nonnull DriverEnvironment environment) {
		super(logger, environment);
		this.console = console;
	}

	protected final void shutdownBase() throws Exception {

		FileUtils.deleteFileReported(tempDirectory);



		console.close();

	}

	@Nonnull
	public Path getTempDirectory() {
		return tempDirectory;
	}

	private static CloudBase instance;

	public static CloudBase getInstance() {
		if (instance == null)
			instance = (CloudBase) CloudDriver.getInstance();

		return instance;
	}
}
