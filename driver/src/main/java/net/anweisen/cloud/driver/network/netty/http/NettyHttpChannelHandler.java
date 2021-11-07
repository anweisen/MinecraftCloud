package net.anweisen.cloud.driver.network.netty.http;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpRequest;
import net.anweisen.cloud.driver.console.LoggingApiUser;
import net.anweisen.cloud.driver.network.http.HttpCodes;
import net.anweisen.cloud.driver.network.http.HttpMethod;
import net.anweisen.cloud.driver.network.http.auth.HttpAuthHandler;
import net.anweisen.cloud.driver.network.http.auth.HttpAuthUser;
import net.anweisen.cloud.driver.network.http.handler.RegisteredHandler;
import net.anweisen.cloud.driver.network.object.HostAndPort;
import net.anweisen.utilities.common.collection.pair.Tuple;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author anweisen | https://github.com/anweisen
 * @since 1.0
 */
public class NettyHttpChannelHandler extends SimpleChannelInboundHandler<HttpRequest> implements LoggingApiUser {

	protected final NettyHttpServer server;
	protected final HostAndPort address;

	protected NettyHttpChannel channel;

	public NettyHttpChannelHandler(@Nonnull NettyHttpServer server, @Nonnull HostAndPort address) {
		this.server = server;
		this.address = address;
	}

	@Override
	public void channelActive(ChannelHandlerContext context) throws Exception {
		channel = new NettyHttpChannel(address, HostAndPort.fromSocketAddress(context.channel().remoteAddress()), context.channel());
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext context, Throwable ex) throws Exception {
		if (!(ex instanceof IOException)) {
			error("Error on channel {}", channel, ex);
		}
	}

	@Override
	protected void channelRead0(ChannelHandlerContext context, HttpRequest request) throws Exception {
		if (request.decoderResult().isFailure()) {
			context.close();
			return;
		}

		URI uri = URI.create(request.uri());

		String fullPath = uri.getPath();
		trace("Received {} on route '{}'", request.method(), fullPath);

		Map<String, String> pathParameters = new LinkedHashMap<>();
		NettyHttpContext httpContext = new NettyHttpContext(uri, channel, server, request, context.channel(), pathParameters);

		HttpMethod method = httpContext.getRequest().getMethod();
		String[] pathEntries = fullPath.split("/");
		String[] handlerPathEntries;
		Tuple<HttpAuthHandler, HttpAuthUser> auth = null;
		for (RegisteredHandler handler : server.getHandlerRegistry().getHandlers()) {
			if (httpContext.cancelNext) break;

			if (!Arrays.asList(handler.getMethods()).contains(method)) continue;
			handlerPathEntries = handler.getPath().split("/");
			if (!checkPath(pathEntries, handlerPathEntries, pathParameters)) continue;

			if (!handler.getPermission().isEmpty()) {
				if (auth == null) {
					auth = Tuple.empty();
					String header = httpContext.getRequest().getHeader("Authorization");
					String[] authorization = header.split(" ");

					if (authorization.length == 2) {
						String type = authorization[0];
						HttpAuthHandler authHandler = server.getAuthRegistry().getAuthMethodHandler(type);
						auth.setFirst(authHandler);

						if (authHandler != null) {
							HttpAuthUser authUser = authHandler.getAuthUser(authorization[1]);
							auth.setSecond(authUser);
						}
					}
				}

				if (auth.getSecond() == null) {
					httpContext.getResponse().setStatusCode(HttpCodes.UNAUTHORIZED);
					break;
				}
				if (!auth.getSecond().hasPermission(handler.getPermission())) {
					httpContext.getResponse().setStatusCode(HttpCodes.UNAUTHORIZED).setBody("Permission " + handler.getPermission() + " required");
					break;
				}

			}

			try {
				handler.execute(httpContext);
			} catch (Exception ex) {
				error("Could not execute http handler for '{}'", fullPath, ex);
			}

		}

		if (!httpContext.cancelSendResponse) {

			if (httpContext.response.getStatusCode() == HttpCodes.NOT_FOUND && httpContext.response.nettyResponse.content().readableBytes() == 0) {
				httpContext.response.nettyResponse.content().writeBytes(("Cannot " + method + " " + fullPath).getBytes(StandardCharsets.UTF_8));
			}

			ChannelFuture channelFuture = context.channel().writeAndFlush(httpContext.response.nettyResponse).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);

			if (httpContext.closeAfter) {
				channelFuture.addListener(ChannelFutureListener.CLOSE);
			}
		}

	}

	private boolean checkPath(@Nonnull String[] pathEntries, @Nonnull String[] handlerPathEntries, @Nonnull Map<String, String> pathParameters) {

		if (pathEntries.length != handlerPathEntries.length)
			return false;

		for (int i = 0; i < handlerPathEntries.length; i++) {

			String handlerPathEntry = handlerPathEntries[i];
			String providedPathEntry = pathEntries[i];

			if (handlerPathEntry.equals("*"))
				continue;
			if (handlerPathEntry.startsWith("{") && handlerPathEntry.endsWith("}")) {
				String pathArgumentName = handlerPathEntry.substring(1, handlerPathEntry.length() - 1);
				pathParameters.put(pathArgumentName, providedPathEntry);
				continue;
			}

			if (!handlerPathEntry.equalsIgnoreCase(providedPathEntry))
				return false;

		}

		return true;
	}
}
