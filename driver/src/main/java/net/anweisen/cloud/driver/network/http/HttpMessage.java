package net.anweisen.cloud.driver.network.http;

import net.anweisen.utilities.common.config.Document;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * @author anweisen | https://github.com/anweisen
 * @since 1.0
 *
 * @see HttpResponse
 * @see HttpRequest
 */
public interface HttpMessage<M extends HttpMessage<?>> {

	@Nonnull
	HttpContext getContext();

	@Nullable
	String getHeader(@Nonnull String name);

	int getHeaderInt(@Nonnull String name);

	boolean getHeaderBoolean(@Nonnull String name);

	boolean hasHeader(@Nonnull String name);

	@Nonnull
	M setHeader(@Nonnull String name, @Nonnull String value);

	@Nonnull
	M removeHeader(@Nonnull String name);

	@Nonnull
	M clearHeaders();

	@Nonnull
	Map<String, String> getHeaders();

	@Nonnull
	HttpVersion getVersion();

	@Nonnull
	M setVersion(@Nonnull HttpVersion version);

	@Nonnull
	byte[] getBody();

	@Nonnull
	String getBodyString();

	@Nonnull
	M setBody(@Nonnull byte[] data);

	@Nonnull
	M setBody(@Nonnull String text);

	@Nonnull
	M setBody(@Nonnull Document document);
}
